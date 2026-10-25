package ru.cashflow.forecast.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ModelSelectionRs(String selectedModel, LocalDate holdoutFrom, int selectionOrigins, int holdoutOrigins,
        BigDecimal selectedHoldoutMae, BigDecimal primaryHoldoutMae, List<CandidateRs> candidates) {
    public record CandidateRs(String model, BigDecimal selectionMae, BigDecimal holdoutMae) { }
}
