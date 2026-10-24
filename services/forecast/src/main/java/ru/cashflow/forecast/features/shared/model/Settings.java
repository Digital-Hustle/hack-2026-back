package ru.cashflow.forecast.features.shared.model;

import java.math.BigDecimal;

public record Settings(int windowWeeks, int horizonDays, BigDecimal bufferAmount, int scenarioCount,
        int minCalibrationOrigins, int calibrationStepDays, Algorithm algorithm) { }
