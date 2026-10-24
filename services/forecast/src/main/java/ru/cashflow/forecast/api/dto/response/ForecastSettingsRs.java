package ru.cashflow.forecast.api.dto.response;

import java.math.BigDecimal;
import ru.cashflow.forecast.features.shared.model.Algorithm;

public record ForecastSettingsRs(int windowWeeks, int horizonDays, BigDecimal bufferAmount, int scenarioCount,
        int minCalibrationOrigins, int calibrationStepDays, Algorithm algorithm) { }
