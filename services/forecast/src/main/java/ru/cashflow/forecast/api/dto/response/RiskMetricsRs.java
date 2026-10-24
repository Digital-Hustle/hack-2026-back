package ru.cashflow.forecast.api.dto.response;

import java.util.List;

public record RiskMetricsRs(Double brierScore, int evaluatedCount, List<CalibrationBinRs> calibrationBins,
        Double interval80Coverage) { }
