package ru.cashflow.forecast.features.forecast.application;

import java.time.Clock;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import ru.cashflow.forecast.features.forecast.model.CalculationInput;
import ru.cashflow.forecast.features.forecast.model.CalculationResult;
import ru.cashflow.forecast.features.risk.application.TrajectoryBootstrap;
import ru.cashflow.forecast.features.risk.model.RiskAvailability;
import ru.cashflow.forecast.features.forecast.model.PointForecast;
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
        var point = engine.calculate(input.pointInput());
        var risk = bootstrap.estimate(input, point);
        var warnings = new ArrayList<>(point.warnings());
        boolean available = risk.estimate().availability() == RiskAvailability.AVAILABLE;
        warnings.add(new Warning(available ? "ESTIMATED_PROBABILITY" : "RISK_UNAVAILABLE", available
                ? "Scenario probability is uncalibrated; review backtest metrics"
                : "Insufficient usable mature origins; probability is unknown, not zero", null));
        point = new PointForecast(point.days(), point.quality(), warnings, point.baselineReplacements(),
                point.expectedMinimumBalance(), point.firstExpectedGapDate(), point.maxExpectedShortfall());
        return new CalculationResult(input.pointInput(), point, risk,
                gaps.detect(input.fingerprint(), point, risk, input.pointInput().settings().bufferAmount()), clock.instant());
    }
}
