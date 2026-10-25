package ru.cashflow.forecast.features.backtest.model;

import java.util.List;

/**
 * Extended quality report of a backtest: the primary model, reference models on the same data, the per-day
 * classification of cash-gap days, the per-origin 30-day classification, and the same with the scenario probability.
 */
public record QualityMetrics(ModelScore primary, List<ModelScore> benchmarks, ConfusionMatrix originMatrix,
        ConfusionMatrix scenarioDailyMatrix, ConfusionMatrix bufferDailyMatrix, double scenarioThreshold,
        ModelSelection selection, List<String> notes) {
    public QualityMetrics {
        benchmarks = List.copyOf(benchmarks);
        notes = List.copyOf(notes);
    }
}
