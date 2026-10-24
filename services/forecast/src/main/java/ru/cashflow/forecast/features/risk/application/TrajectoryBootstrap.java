package ru.cashflow.forecast.features.risk.application;

import org.springframework.stereotype.Component;

import ru.cashflow.forecast.features.forecast.application.PointForecastEngine;
import ru.cashflow.forecast.features.forecast.application.ResidualExtractor;
import ru.cashflow.forecast.features.forecast.application.SnapshotValidator;
import ru.cashflow.forecast.features.forecast.model.CalculationInput;
import ru.cashflow.forecast.features.forecast.model.PointForecast;
import ru.cashflow.forecast.features.risk.model.ErrorTrajectory;
import ru.cashflow.forecast.features.risk.model.RiskAvailability;
import ru.cashflow.forecast.features.risk.model.RiskDay;
import ru.cashflow.forecast.features.risk.model.RiskEstimate;
import ru.cashflow.forecast.features.risk.model.RiskResult;
import ru.cashflow.forecast.features.shared.model.InputException;
import ru.cashflow.forecast.features.shared.model.Money;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

@Component
public class TrajectoryBootstrap {
    private final PointForecastEngine engine;
    private final CalibrationScorer scorer;
    private static final List<String> ASSUMPTIONS =
            List.of(
                    "Known and confirmed recurring dates and amounts are fixed; settlement delays"
                        + " are not modeled",
                    "Whole paired 30-day residual error trajectories are sampled with replacement",
                    "Historical errors are assumed transferable; overlapping origins are not"
                        + " independent",
                    "Residual flows are clipped at zero; the scenario estimate is not a calibrated"
                        + " probability",
                    "Calendar: Monday-Friday only, without official holidays; PRNG:"
                        + " java.util.Random LCG48 v1");

    public TrajectoryBootstrap(
            PointForecastEngine engine, ResidualExtractor extractor, SnapshotValidator validator) {
        this.engine = engine;
        this.scorer = new CalibrationScorer(extractor, validator);
    }

    public RiskResult estimate(CalculationInput input, PointForecast point) {
        var outer = input.pointInput();
        var origins =
                input.calibrationOrigins().stream()
                        .sorted(Comparator.comparing(origin -> origin.input().asOfDate()))
                        .toList();
        var cutoffs = new HashSet<LocalDate>();
        LocalDate previous = null;
        for (var origin : origins) {
            var nested = origin.input();
            SnapshotValidator.validateHistoricalCutoff(nested);
            boolean compatible =
                    nested.companyId().equals(outer.companyId())
                            && nested.accountId().equals(outer.accountId())
                            && nested.scope() == outer.scope()
                            && nested.algorithmVersion().equals(outer.algorithmVersion())
                            && nested.settings().windowWeeks() == outer.settings().windowWeeks()
                            && nested.settings().algorithm() == outer.settings().algorithm()
                            && nested.inputDataVersion() <= outer.inputDataVersion();
            if (!compatible
                    || !cutoffs.add(nested.asOfDate())
                    || nested.asOfDate().plusDays(30).isAfter(outer.asOfDate())
                    || previous != null
                            && ChronoUnit.DAYS.between(previous, nested.asOfDate())
                                            % outer.settings().calibrationStepDays()
                                    != 0) {
                throw new InputException(
                        "VALIDATION_FAILED",
                        "calibrationOrigins",
                        "Duplicate, incompatible, unmatured or off-grid origin");
            }
            previous = nested.asOfDate();
        }
        var trajectories = new ArrayList<ErrorTrajectory>();
        boolean incomplete = false;
        for (var origin : origins) {
            try {
                PointForecast fitted = engine.calculate(origin.input());
                trajectories.add(scorer.score(origin, fitted));
            } catch (InputException exception) {
                if (!java.util.Set.of(
                                "INSUFFICIENT_HISTORY",
                                "UNRESOLVED_PLAN",
                                "INPUT_INCOMPLETE",
                                "BALANCE_ANCHOR_REQUIRED")
                        .contains(exception.code())) {
                    throw exception;
                }
                incomplete = true;
            }
        }
        if (trajectories.size() < outer.settings().minCalibrationOrigins()) {
            return unavailable(
                    incomplete
                            ? RiskAvailability.INPUT_INCOMPLETE
                            : RiskAvailability.INSUFFICIENT_HISTORY,
                    trajectories.size());
        }
        Random random = new Random(seed(input.fingerprint(), outer.algorithmVersion()));
        var scenarios = new ArrayList<List<BigDecimal>>();
        int periodBreaches = 0;
        int count = outer.settings().scenarioCount();
        for (int scenario = 0; scenario < count; scenario++) {
            var errors = trajectories.get(random.nextInt(trajectories.size())).errors();
            var path = new ArrayList<BigDecimal>();
            BigDecimal balance = outer.startBalance();
            boolean breached = false;
            for (int horizon = 0; horizon < 30; horizon++) {
                var day = point.days().get(horizon);
                var error = errors.get(horizon);
                BigDecimal residualIn =
                        Money.cents(day.residualIn().add(error.inflow()).max(Money.ZERO));
                BigDecimal residualOut =
                        Money.cents(day.residualOut().add(error.outflow()).max(Money.ZERO));
                balance =
                        Money.cents(
                                balance.add(day.plannedIn())
                                        .add(day.recurringIn())
                                        .add(residualIn)
                                        .subtract(day.plannedOut())
                                        .subtract(day.recurringOut())
                                        .subtract(residualOut));
                path.add(balance);
                breached |= balance.signum() < 0;
            }
            if (breached) {
                periodBreaches++;
            }
            scenarios.add(path);
        }
        var days = new ArrayList<RiskDay>();
        for (int horizon = 0; horizon < 30; horizon++) {
            int index = horizon;
            var balances = scenarios.stream().map(path -> path.get(index)).toList();
            long belowZero = balances.stream().filter(balance -> balance.signum() < 0).count();
            long belowBuffer =
                    balances.stream()
                            .filter(
                                    balance ->
                                            balance.compareTo(outer.settings().bufferAmount()) < 0)
                            .count();
            days.add(
                    new RiskDay(
                            Money.quantile(balances, new BigDecimal("0.1")),
                            Money.quantile(balances, new BigDecimal("0.5")),
                            Money.quantile(balances, new BigDecimal("0.9")),
                            (double) belowZero / count,
                            (double) belowBuffer / count));
        }
        return new RiskResult(
                new RiskEstimate(
                        RiskAvailability.AVAILABLE,
                        "HISTORICAL_TRAJECTORY_BOOTSTRAP",
                        (double) periodBreaches / count,
                        count,
                        trajectories.size(),
                        false,
                        ASSUMPTIONS),
                days,
                scenarios);
    }

    private RiskResult unavailable(RiskAvailability availability, int originCount) {
        return new RiskResult(
                new RiskEstimate(
                        availability,
                        "HISTORICAL_TRAJECTORY_BOOTSTRAP",
                        null,
                        0,
                        originCount,
                        false,
                        ASSUMPTIONS),
                java.util.Collections.nCopies(30, new RiskDay(null, null, null, null, null)),
                List.of());
    }

    private long seed(String fingerprint, String version) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest((fingerprint + version).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
