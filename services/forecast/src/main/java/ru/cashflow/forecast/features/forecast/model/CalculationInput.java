package ru.cashflow.forecast.features.forecast.model;

import java.util.List;
import ru.cashflow.forecast.features.risk.model.CalibrationOrigin;

public record CalculationInput(ForecastInput pointInput, String fingerprint,
        List<CalibrationOrigin> calibrationOrigins) {
    public CalculationInput {
        calibrationOrigins = List.copyOf(calibrationOrigins);
    }
}
