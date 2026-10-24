package ru.cashflow.forecast.features.backtest.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import ru.cashflow.forecast.features.backtest.model.BacktestInput;
import ru.cashflow.forecast.features.backtest.model.BacktestResult;
import ru.cashflow.forecast.features.backtest.model.OriginResult;
import ru.cashflow.forecast.features.forecast.application.GapDetector;
import ru.cashflow.forecast.features.forecast.application.PointForecastEngine;
import ru.cashflow.forecast.features.forecast.application.SnapshotValidator;
import ru.cashflow.forecast.features.risk.application.TrajectoryBootstrap;
import ru.cashflow.forecast.features.forecast.model.CalculationInput;
import ru.cashflow.forecast.features.risk.model.RiskAvailability;
import ru.cashflow.forecast.features.risk.model.RiskDay;
import ru.cashflow.forecast.features.risk.model.RiskEstimate;
import ru.cashflow.forecast.features.risk.model.RiskResult;
import ru.cashflow.forecast.features.shared.model.DailyStatus;
import ru.cashflow.forecast.features.shared.model.DayFact;
import ru.cashflow.forecast.features.shared.model.InputException;
import ru.cashflow.forecast.features.shared.model.Warning;

@Service
public class BacktestService {
    private final PointForecastEngine engine;
    private final TrajectoryBootstrap bootstrap;
    private final GapDetector gaps;
    private final SnapshotValidator validator;

    public BacktestService(PointForecastEngine engine, TrajectoryBootstrap bootstrap, GapDetector gaps,
            SnapshotValidator validator) {
        this.engine = engine;
        this.bootstrap = bootstrap;
        this.gaps = gaps;
        this.validator = validator;
    }

    public BacktestResult evaluate(BacktestInput input) {
        validator.validateSettings(input.settings());
        var request = input.request();
        require(request.accountId().equals(input.accountId()) && request.windowWeeks() == input.settings().windowWeeks()
                && !request.firstOrigin().isAfter(request.lastOrigin()) && request.stepDays() >= 7 && request.stepDays() <= 30,
                "request", "Invalid experiment grid or settings");
        long originCount = ChronoUnit.DAYS.between(request.firstOrigin(), request.lastOrigin()) / request.stepDays() + 1;
        require(originCount <= 100, "request", "Too many requested origins");
        var grid = new ArrayList<LocalDate>();
        for (int index = 0; index < originCount; index++) {
            grid.add(request.firstOrigin().plusDays((long) index * request.stepDays()));
        }
        Map<LocalDate, CalculationInput> snapshots = new HashMap<>();
        for (var origin : input.originSnapshots()) {
            var point = origin.pointInput();
            require(point.companyId().equals(input.companyId()) && point.accountId().equals(input.accountId())
                    && point.inputDataVersion() <= input.inputDataVersion() && point.scope() == request.scope()
                    && point.settings().equals(input.settings()) && point.algorithmVersion().equals("WEEKDAY_MEAN_V1"),
                    "originSnapshots", "Origin differs from fixed experiment scope or settings");
            require(grid.contains(point.asOfDate()) && snapshots.putIfAbsent(point.asOfDate(), origin) == null,
                    "originSnapshots", "Duplicate or off-grid origin");
        }
        Map<LocalDate, DayFact> actualByDate = new HashMap<>();
        for (var day : input.actualDays()) {
            require(actualByDate.putIfAbsent(day.date(), day) == null && day.date().isAfter(request.firstOrigin())
                    && !day.date().isAfter(grid.getLast().plusDays(30)), "actualDays", "Duplicate or out-of-range scoring day");
            require(day.status() == DailyStatus.MISSING ? day.inflow() == null && day.outflow() == null && day.balanceEod() == null
                    : day.inflow() != null && day.outflow() != null && day.inflow().signum() >= 0 && day.outflow().signum() >= 0,
                    "actualDays", "Invalid scoring status or flows");
            require(day.status() != DailyStatus.ZERO_CONFIRMED || day.inflow().signum() == 0 && day.outflow().signum() == 0 && day.paymentCount() == 0,
                    "actualDays", "Confirmed zero day contains flow");
        }
        var scorer = new BacktestScorer();
        var origins = new ArrayList<OriginResult>();
        var warnings = new ArrayList<Warning>();
        warnings.add(new Warning("OVERLAPPING_ORIGINS", "Metrics aggregate forecast-instance x day; overlapping windows are not independent", null));
        boolean partial = false;
        int evaluated = 0;
        for (LocalDate cutoff : grid) {
            var snapshot = snapshots.get(cutoff);
            String skipReason = snapshot == null ? "HISTORICAL_INPUT_UNAVAILABLE" : null;
            var actual = new ArrayList<DayFact>();
            for (int horizon = 1; horizon <= 30; horizon++) {
                var day = actualByDate.get(cutoff.plusDays(horizon));
                if (day != null) {
                    actual.add(day);
                }
            }
            if (skipReason == null && (actual.size() != 30 || actual.stream().anyMatch(day -> !day.complete()))) {
                skipReason = "SCORING_PERIOD_INCOMPLETE";
            }
            if (skipReason == null && actual.stream().noneMatch(day -> day.balanceEod() != null)) {
                skipReason = "EOD_UNAVAILABLE";
            }
            if (skipReason != null) {
                origins.add(new OriginResult(cutoff, "SKIPPED", skipReason, null, 0));
                partial = true;
                continue;
            }
            try {
                var point = engine.calculate(snapshot.pointInput());
                RiskResult risk = request.evaluateRisk() ? bootstrap.estimate(snapshot, point) : new RiskResult(
                        new RiskEstimate(RiskAvailability.INSUFFICIENT_HISTORY, "HISTORICAL_TRAJECTORY_BOOTSTRAP", null, 0, 0, false, List.of()),
                        java.util.Collections.nCopies(30, new RiskDay(null, null, null, null, null)), List.of());
                var forecastGaps = gaps.detect(snapshot.fingerprint(), point, risk, input.settings().bufferAmount());
                var originResult = scorer.addOrigin(cutoff, point, risk, forecastGaps, actual);
                origins.add(originResult);
                evaluated++;
                partial |= originResult.evaluatedDays() != 30 || request.evaluateRisk() && risk.estimate().availability() != RiskAvailability.AVAILABLE;
                if (request.evaluateRisk() && risk.estimate().availability() != RiskAvailability.AVAILABLE) {
                    warnings.add(new Warning("RISK_UNAVAILABLE", "Requested risk unavailable at this origin", cutoff));
                }
                warnings.addAll(point.warnings());
            } catch (InputException exception) {
                if (!java.util.Set.of("INSUFFICIENT_HISTORY", "BALANCE_ANCHOR_REQUIRED", "UNRESOLVED_PLAN").contains(exception.code())) {
                    throw exception;
                }
                origins.add(new OriginResult(cutoff, "SKIPPED", exception.code(), null, 0));
                partial = true;
            }
        }
        if (evaluated == 0) {
            throw new InputException("INSUFFICIENT_HISTORY", "originSnapshots", "No origin could be evaluated");
        }
        var metrics = scorer.finish();
        return new BacktestResult(partial ? "PARTIAL" : "COMPLETE", "WEEKDAY_MEAN_V1", evaluated, grid.size() - evaluated,
                metrics.evaluatedDays(), metrics.maeBalance(), metrics.rmseBalance(), metrics.horizons(), metrics.gapMetrics(),
                metrics.riskMetrics(), origins, warnings);
    }

    private void require(boolean condition, String field, String message) {
        if (!condition) {
            throw new InputException("VALIDATION_FAILED", field, message);
        }
    }
}
