package ru.cashflow.orchestrator.features.settings.model;

import java.math.BigDecimal;
import java.util.Set;

/** ForecastSettings of the Forecast contract (handoff §4). Only the documented values are accepted. */
public record ForecastSettings(int windowWeeks, int horizonDays, BigDecimal bufferAmount, int scenarioCount,
                               int minCalibrationOrigins, int calibrationStepDays, String algorithm) {

    public static final String ALGORITHM = "WEEKDAY_MEAN_V1";
    /** Opt-in estimator; the Forecast backtest always scores both next to each other. */
    public static final String ROBUST_ALGORITHM = "WEEKDAY_ROBUST_V2";
    public static final Set<String> SUPPORTED_ALGORITHMS = Set.of(ALGORITHM, ROBUST_ALGORITHM);

    public static ForecastSettings defaults() {
        return new ForecastSettings(12, 30, new BigDecimal("0.00"), 2000, 12, 7, ALGORITHM);
    }

    public boolean valid() {
        return Set.of(8, 10, 12).contains(windowWeeks) && horizonDays == 30 && bufferAmount != null
                && bufferAmount.signum() >= 0 && bufferAmount.scale() <= 2 && scenarioCount >= 100
                && scenarioCount <= 5000 && minCalibrationOrigins >= 12 && minCalibrationOrigins <= 100
                && calibrationStepDays == 7 && algorithm != null && SUPPORTED_ALGORITHMS.contains(algorithm);
    }
}
