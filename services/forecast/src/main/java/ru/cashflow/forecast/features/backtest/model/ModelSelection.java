package ru.cashflow.forecast.features.backtest.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Honest model/window choice: the candidate with the lowest MAE on the earlier origins (selection part) is chosen,
 * and its error is then reported on the later origins it was not chosen on (holdout part).
 */
public record ModelSelection(String selectedModel, LocalDate holdoutFrom, int selectionOrigins, int holdoutOrigins,
        BigDecimal selectedHoldoutMae, BigDecimal primaryHoldoutMae, List<Candidate> candidates) {
    public ModelSelection {
        candidates = List.copyOf(candidates);
    }

    public record Candidate(String model, BigDecimal selectionMae, BigDecimal holdoutMae) { }
}
