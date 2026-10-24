package ru.cashflow.forecast.features.backtest.model;

import java.util.List;

public record RiskMetrics(Double brierScore, int evaluatedCount, List<CalibrationBin> calibrationBins,
        Double interval80Coverage) {
    public RiskMetrics { calibrationBins = List.copyOf(calibrationBins); }
}
