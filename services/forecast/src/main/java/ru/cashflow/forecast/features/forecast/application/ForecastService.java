package ru.cashflow.forecast.features.forecast.application;

import java.time.Clock;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import ru.cashflow.forecast.features.forecast.model.CalculationInput;
import ru.cashflow.forecast.features.forecast.model.CalculationResult;
import ru.cashflow.forecast.features.risk.application.TrajectoryBootstrap;
import ru.cashflow.forecast.features.risk.model.RiskAvailability;
import ru.cashflow.forecast.features.forecast.model.PointForecast;
import ru.cashflow.forecast.features.shared.model.CalcTrace;
import ru.cashflow.forecast.features.shared.model.Warning;

@Service
public class ForecastService {
    private final PointForecastEngine engine;
    private final TrajectoryBootstrap bootstrap;
    private final GapDetector gaps;
    private final Clock clock;

    public ForecastService(PointForecastEngine engine, TrajectoryBootstrap bootstrap, GapDetector gaps, Clock clock) {
        this.engine = engine;
        this.bootstrap = bootstrap;
        this.gaps = gaps;
        this.clock = clock;
    }

    public CalculationResult calculate(CalculationInput input) {
        var trace = CalcTrace.on();
        var point = engine.calculate(input.pointInput(), trace);
        var risk = bootstrap.estimate(input, point);
        var estimate = risk.estimate();
        trace.step("RISK", "Scenario risk: whole 30-day historical error trajectories resampled around the point path",
                CalcTrace.data("availability", estimate.availability().name(), "method", estimate.method(),
                        "calibrationOrigins", String.valueOf(estimate.calibrationOriginCount()),
                        "requiredOrigins", String.valueOf(input.pointInput().settings().minCalibrationOrigins()),
                        "scenarios", String.valueOf(estimate.scenarioCount()),
                        "probabilityMin30BelowZero", estimate.probability() == null ? "unknown (not zero)"
                                : String.format(java.util.Locale.ROOT, "%.4f", estimate.probability()),
                        "calibrated", String.valueOf(estimate.probabilityCalibrated()),
                        "day30P10", text(risk.days().get(29).balanceP10()), "day30P50", text(risk.days().get(29).balanceP50()),
                        "day30P90", text(risk.days().get(29).balanceP90())));
        var warnings = new ArrayList<>(point.warnings());
        boolean available = risk.estimate().availability() == RiskAvailability.AVAILABLE;
        warnings.add(new Warning(available ? "ESTIMATED_PROBABILITY" : "RISK_UNAVAILABLE", available
                ? "Scenario probability is uncalibrated; review backtest metrics"
                : "Insufficient usable mature origins; probability is unknown, not zero", null));
        point = new PointForecast(point.days(), point.quality(), warnings, point.baselineReplacements(),
                point.expectedMinimumBalance(), point.firstExpectedGapDate(), point.maxExpectedShortfall());
        var episodes = gaps.detect(input.fingerprint(), point, risk, input.pointInput().settings().bufferAmount());
        for (var episode : episodes) {
            trace.step("GAP", episode.kind() + " " + episode.fromDate() + ".." + episode.toDate(), CalcTrace.data(
                    "threshold", episode.threshold().toPlainString(),
                    "expectedMinimumBalance", episode.expectedMinimumBalance().toPlainString(),
                    "expectedShortfall", episode.expectedShortfall().toPlainString(),
                    "probability", episode.probability() == null ? "unknown" : String.format(java.util.Locale.ROOT, "%.4f", episode.probability())));
        }
        if (episodes.isEmpty()) {
            trace.step("GAP", "No cash-gap or buffer episode on the 30-day horizon");
        }
        return new CalculationResult(input.pointInput(), point, risk, episodes, clock.instant(), trace.steps());
    }

    private static String text(java.math.BigDecimal value) {
        return value == null ? "n/a" : value.toPlainString();
    }
}
