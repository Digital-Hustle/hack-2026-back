package ru.cashflow.forecast.features.backtest.application;

import org.springframework.stereotype.Service;

import ru.cashflow.forecast.features.backtest.model.BacktestInput;
import ru.cashflow.forecast.features.backtest.model.Benchmark;
import ru.cashflow.forecast.features.backtest.model.BacktestResult;
import ru.cashflow.forecast.features.backtest.model.OriginResult;
import ru.cashflow.forecast.features.forecast.application.GapDetector;
import ru.cashflow.forecast.features.forecast.application.PointForecastEngine;
import ru.cashflow.forecast.features.forecast.application.SnapshotValidator;
import ru.cashflow.forecast.features.forecast.model.CalculationInput;
import ru.cashflow.forecast.features.forecast.model.Flows;
import ru.cashflow.forecast.features.forecast.model.ForecastInput;
import ru.cashflow.forecast.features.shared.model.Algorithm;
import ru.cashflow.forecast.features.shared.model.CalcTrace;
import ru.cashflow.forecast.features.shared.model.Money;
import ru.cashflow.forecast.features.shared.model.Settings;
import ru.cashflow.forecast.features.risk.application.TrajectoryBootstrap;
import ru.cashflow.forecast.features.risk.model.RiskAvailability;
import ru.cashflow.forecast.features.risk.model.RiskDay;
import ru.cashflow.forecast.features.risk.model.RiskEstimate;
import ru.cashflow.forecast.features.risk.model.RiskResult;
import ru.cashflow.forecast.features.shared.model.DailyStatus;
import ru.cashflow.forecast.features.shared.model.DayFact;
import ru.cashflow.forecast.features.shared.model.InputException;
import ru.cashflow.forecast.features.shared.model.Warning;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BacktestService {
    private final PointForecastEngine engine;
    private final TrajectoryBootstrap bootstrap;
    private final GapDetector gaps;
    private final SnapshotValidator validator;

    public BacktestService(
            PointForecastEngine engine,
            TrajectoryBootstrap bootstrap,
            GapDetector gaps,
            SnapshotValidator validator) {
        this.engine = engine;
        this.bootstrap = bootstrap;
        this.gaps = gaps;
        this.validator = validator;
    }

    public BacktestResult evaluate(BacktestInput input) {
        validator.validateSettings(input.settings());
        var request = input.request();
        require(
                request.accountId().equals(input.accountId())
                        && request.windowWeeks() == input.settings().windowWeeks()
                        && !request.firstOrigin().isAfter(request.lastOrigin())
                        && request.stepDays() >= 7
                        && request.stepDays() <= 30,
                "request",
                "Invalid experiment grid or settings");
        long originCount =
                ChronoUnit.DAYS.between(request.firstOrigin(), request.lastOrigin())
                                / request.stepDays()
                        + 1;
        require(originCount <= 100, "request", "Too many requested origins");
        var grid = new ArrayList<LocalDate>();
        for (int index = 0; index < originCount; index++) {
            grid.add(request.firstOrigin().plusDays((long) index * request.stepDays()));
        }
        Map<LocalDate, CalculationInput> snapshots = new HashMap<>();
        for (var origin : input.originSnapshots()) {
            var point = origin.pointInput();
            SnapshotValidator.validateHistoricalCutoff(point);
            require(
                    point.companyId().equals(input.companyId())
                            && point.accountId().equals(input.accountId())
                            && point.inputDataVersion() <= input.inputDataVersion()
                            && point.scope() == request.scope()
                            && point.settings().equals(input.settings())
                            && point.algorithmVersion().equals(input.settings().algorithm().name()),
                    "originSnapshots",
                    "Origin differs from fixed experiment scope or settings");
            require(
                    grid.contains(point.asOfDate())
                            && snapshots.putIfAbsent(point.asOfDate(), origin) == null,
                    "originSnapshots",
                    "Duplicate or off-grid origin");
        }
        Map<LocalDate, DayFact> actualByDate = new HashMap<>();
        for (var day : input.actualDays()) {
            require(
                    actualByDate.putIfAbsent(day.date(), day) == null
                            && day.date().isAfter(request.firstOrigin())
                            && !day.date().isAfter(grid.getLast().plusDays(30)),
                    "actualDays",
                    "Duplicate or out-of-range scoring day");
            require(
                    day.status() == DailyStatus.MISSING
                            ? day.inflow() == null
                                    && day.outflow() == null
                                    && day.balanceEod() == null
                            : day.inflow() != null
                                    && day.outflow() != null
                                    && day.inflow().signum() >= 0
                                    && day.outflow().signum() >= 0,
                    "actualDays",
                    "Invalid scoring status or flows");
            require(
                    day.status() != DailyStatus.ZERO_CONFIRMED
                            || day.inflow().signum() == 0
                                    && day.outflow().signum() == 0
                                    && day.paymentCount() == 0,
                    "actualDays",
                    "Confirmed zero day contains flow");
        }
        String primaryName = modelName(input.settings().algorithm(), request.windowWeeks());
        var scorer = new BacktestScorer(primaryName, describe(input.settings().algorithm(), request.windowWeeks()),
                input.settings().bufferAmount());
        var trace = CalcTrace.on();
        trace.step("EXPERIMENT", "Rolling-origin backtest: the same engine is refitted at every cutoff on data known then",
                CalcTrace.data("primaryModel", primaryName, "scope", request.scope().name(),
                        "firstOrigin", request.firstOrigin().toString(), "lastOrigin", request.lastOrigin().toString(),
                        "stepDays", String.valueOf(request.stepDays()), "gridOrigins", String.valueOf(grid.size()),
                        "suppliedOrigins", String.valueOf(input.originSnapshots().size()),
                        "evaluateRisk", String.valueOf(request.evaluateRisk()), "horizonDays", "30"));
        var origins = new ArrayList<OriginResult>();
        var warnings = new ArrayList<Warning>();
        warnings.add(
                new Warning(
                        "OVERLAPPING_ORIGINS",
                        "Metrics aggregate forecast-instance x day; overlapping windows are not"
                            + " independent",
                        null));
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
            if (skipReason == null
                    && (actual.size() != 30 || actual.stream().anyMatch(day -> !day.complete()))) {
                skipReason = "SCORING_PERIOD_INCOMPLETE";
            }
            if (skipReason == null && actual.stream().noneMatch(day -> day.balanceEod() != null)) {
                skipReason = "EOD_UNAVAILABLE";
            }
            if (skipReason != null) {
                trace.step("ORIGIN", "Skipped " + cutoff, CalcTrace.data("reason", skipReason));
                origins.add(new OriginResult(cutoff, "SKIPPED", skipReason, null, 0));
                partial = true;
                continue;
            }
            try {
                var point = engine.calculate(snapshot.pointInput());
                RiskResult risk =
                        request.evaluateRisk()
                                ? bootstrap.estimate(snapshot, point)
                                : new RiskResult(
                                        new RiskEstimate(
                                                RiskAvailability.INSUFFICIENT_HISTORY,
                                                "HISTORICAL_TRAJECTORY_BOOTSTRAP",
                                                null,
                                                0,
                                                0,
                                                false,
                                                List.of()),
                                        java.util.Collections.nCopies(
                                                30, new RiskDay(null, null, null, null, null)),
                                        List.of());
                var forecastGaps =
                        gaps.detect(
                                snapshot.fingerprint(),
                                point,
                                risk,
                                input.settings().bufferAmount());
                var references = benchmarks(snapshot.pointInput(), request.windowWeeks());
                var originResult = scorer.addOrigin(cutoff, point, risk, forecastGaps, actual, references);
                trace.step("ORIGIN", "Evaluated " + cutoff, CalcTrace.data(
                        "trainingWindow", snapshot.pointInput().asOfDate().minusDays(request.windowWeeks() * 7L - 1) + ".."
                                + snapshot.pointInput().asOfDate(),
                        "startBalance", snapshot.pointInput().startBalance().toPlainString(),
                        "scoredDays", String.valueOf(originResult.evaluatedDays()),
                        "mae", originResult.maeBalance() == null ? "n/a" : originResult.maeBalance().toPlainString(),
                        "forecastMinimum", point.expectedMinimumBalance().toPlainString(),
                        "forecastGapEpisodes", String.valueOf(forecastGaps.stream().filter(gap -> gap.kind().equals("CASH_GAP")).count()),
                        "risk", risk.estimate().availability().name(),
                        "referenceModels", String.valueOf(references.size())));
                origins.add(originResult);
                evaluated++;
                partial |=
                        originResult.evaluatedDays() != 30
                                || request.evaluateRisk()
                                        && risk.estimate().availability()
                                                != RiskAvailability.AVAILABLE;
                if (request.evaluateRisk()
                        && risk.estimate().availability() != RiskAvailability.AVAILABLE) {
                    warnings.add(
                            new Warning(
                                    "RISK_UNAVAILABLE",
                                    "Requested risk unavailable at this origin",
                                    cutoff));
                }
                warnings.addAll(point.warnings());
            } catch (InputException exception) {
                if (!java.util.Set.of(
                                "INSUFFICIENT_HISTORY",
                                "BALANCE_ANCHOR_REQUIRED",
                                "UNRESOLVED_PLAN")
                        .contains(exception.code())) {
                    throw exception;
                }
                trace.step("ORIGIN", "Skipped " + cutoff, CalcTrace.data("reason", exception.code()));
                origins.add(new OriginResult(cutoff, "SKIPPED", exception.code(), null, 0));
                partial = true;
            }
        }
        if (evaluated == 0) {
            throw new InputException(
                    "INSUFFICIENT_HISTORY", "originSnapshots", "No origin could be evaluated");
        }
        var metrics = scorer.finish();
        summarize(trace, metrics);
        return new BacktestResult(
                partial ? "PARTIAL" : "COMPLETE",
                input.settings().algorithm().name(),
                evaluated,
                grid.size() - evaluated,
                metrics.evaluatedDays(),
                metrics.maeBalance(),
                metrics.rmseBalance(),
                metrics.horizons(),
                metrics.gapMetrics(),
                metrics.riskMetrics(),
                origins,
                warnings,
                metrics.quality(),
                trace.steps());
    }

    /**
     * Reference models for the same origin, all built strictly from the origin snapshot (data known at the cutoff):
     * naive last balance, seasonal naive (last known same weekday), and the engine with the other estimator and
     * with shorter training windows. A reference that cannot be computed at this origin is omitted.
     */
    private List<Benchmark> benchmarks(ForecastInput input, int windowWeeks) {
        var result = new ArrayList<Benchmark>();
        var naiveBalances = new ArrayList<BigDecimal>();
        var zeroFlows = new ArrayList<Flows>();
        for (int horizon = 0; horizon < 30; horizon++) {
            naiveBalances.add(input.startBalance());
            zeroFlows.add(Flows.ZERO);
        }
        result.add(new Benchmark(BacktestScorer.NAIVE, "Balance stays equal to the cutoff balance B(T)", false,
                naiveBalances, zeroFlows));
        var lastByWeekday = new java.util.EnumMap<DayOfWeek, Flows>(DayOfWeek.class);
        input.dailyHistory().stream().filter(DayFact::complete).sorted(java.util.Comparator.comparing(DayFact::date))
                .forEach(day -> lastByWeekday.put(day.date().getDayOfWeek(), new Flows(day.inflow(), day.outflow())));
        var seasonalBalances = new ArrayList<BigDecimal>();
        var seasonalFlows = new ArrayList<Flows>();
        BigDecimal balance = input.startBalance();
        for (int horizon = 1; horizon <= 30; horizon++) {
            var flows = lastByWeekday.getOrDefault(input.asOfDate().plusDays(horizon).getDayOfWeek(), Flows.ZERO);
            balance = Money.cents(balance.add(flows.inflow()).subtract(flows.outflow()));
            seasonalBalances.add(balance);
            seasonalFlows.add(flows);
        }
        result.add(new Benchmark("SEASONAL_NAIVE_7D", "Each day repeats the last known flows of the same weekday", false,
                seasonalBalances, seasonalFlows));
        for (var algorithm : Algorithm.values()) {
            for (int window : List.of(8, 10, 12)) {
                if (window > windowWeeks || algorithm == input.settings().algorithm() && window == windowWeeks) {
                    continue;
                }
                try {
                    var fitted = engine.calculate(variant(input, algorithm, window));
                    result.add(new Benchmark(modelName(algorithm, window), describe(algorithm, window), true,
                            fitted.days().stream().map(day -> day.balance()).toList(),
                            fitted.days().stream().map(day -> new Flows(day.inflow(), day.outflow())).toList()));
                } catch (InputException ignored) {
                    // Not computable at this origin (e.g. a weekday without complete days in a shorter window).
                }
            }
        }
        return result;
    }

    private static ForecastInput variant(ForecastInput input, Algorithm algorithm, int windowWeeks) {
        var settings = input.settings();
        var changed = new Settings(windowWeeks, settings.horizonDays(), settings.bufferAmount(), settings.scenarioCount(),
                settings.minCalibrationOrigins(), settings.calibrationStepDays(), algorithm);
        return new ForecastInput(input.snapshotId(), input.companyId(), input.accountId(), input.asOfDate(),
                input.knowledgeCutoff(), input.timezone(), input.inputDataVersion(), algorithm.name(), changed,
                input.scope(), input.startBalance(), input.startBalanceDate(), input.historyFrom(), input.dailyHistory(),
                input.payments(), input.knownPlans(), input.recurringPatterns(), input.completeRanges());
    }

    static String modelName(Algorithm algorithm, int windowWeeks) {
        return algorithm.name() + "@" + windowWeeks + "w";
    }

    private static String describe(Algorithm algorithm, int windowWeeks) {
        return (algorithm == Algorithm.WEEKDAY_MEAN_V1
                ? "Weekday mean of residual flows"
                : "Holiday-aware, outlier-capped, shrunk weekday level of residual flows")
                + " + recurring + plans, training window " + windowWeeks + " weeks";
    }

    private static void summarize(CalcTrace trace, ru.cashflow.forecast.features.backtest.model.ScoringResult metrics) {
        var quality = metrics.quality();
        var primary = quality.primary();
        trace.step("METRICS", "Primary model error over forecast-instance x day", CalcTrace.data(
                "model", primary.model(), "days", String.valueOf(primary.evaluatedDays()),
                "MAE", text(primary.maeBalance()), "MSE", text(primary.mseBalance()), "RMSE", text(primary.rmseBalance()),
                "WAPE", text(primary.wapeBalance()), "skillVsNaive", text(primary.skillScoreVsNaive()),
                "inflowMAE", text(primary.inflowMae()), "outflowMAE", text(primary.outflowMae())));
        var matrix = primary.dailyMatrix();
        trace.step("CONFUSION_MATRIX", "Daily cash-gap classification (balance < 0)", CalcTrace.data(
                "TP", String.valueOf(matrix.truePositive()), "FP", String.valueOf(matrix.falsePositive()),
                "FN", String.valueOf(matrix.falseNegative()), "TN", String.valueOf(matrix.trueNegative()),
                "accuracy", text(matrix.accuracy()), "precision", text(matrix.precision()), "recall", text(matrix.recall()),
                "F1", text(matrix.f1()), "balancedAccuracy", text(matrix.balancedAccuracy()),
                "majorityBaselineAccuracy", text(matrix.majorityBaselineAccuracy())));
        for (var benchmark : quality.benchmarks()) {
            trace.step("BENCHMARK", benchmark.model(), CalcTrace.data("MAE", text(benchmark.maeBalance()),
                    "RMSE", text(benchmark.rmseBalance()), "skillVsNaive", text(benchmark.skillScoreVsNaive()),
                    "days", String.valueOf(benchmark.evaluatedDays())));
        }
        if (quality.selection() != null) {
            var selection = quality.selection();
            trace.step("MODEL_SELECTION", "Chosen on earlier origins, measured on later ones", CalcTrace.data(
                    "selected", selection.selectedModel(), "holdoutFrom", selection.holdoutFrom().toString(),
                    "selectedHoldoutMAE", text(selection.selectedHoldoutMae()),
                    "primaryHoldoutMAE", text(selection.primaryHoldoutMae())));
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return "n/a";
        }
        if (value instanceof Double number) {
            return String.format(java.util.Locale.ROOT, "%.4f", number);
        }
        return value instanceof BigDecimal money ? money.toPlainString() : value.toString();
    }

    private void require(boolean condition, String field, String message) {
        if (!condition) {
            throw new InputException("VALIDATION_FAILED", field, message);
        }
    }
}
