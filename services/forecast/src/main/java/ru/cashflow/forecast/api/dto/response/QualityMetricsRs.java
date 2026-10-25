package ru.cashflow.forecast.api.dto.response;

import java.util.List;

public record QualityMetricsRs(ModelScoreRs primary, List<ModelScoreRs> benchmarks, ConfusionMatrixRs originMatrix,
        ConfusionMatrixRs scenarioDailyMatrix, ConfusionMatrixRs bufferDailyMatrix, double scenarioThreshold,
        ModelSelectionRs selection, List<String> notes) { }
