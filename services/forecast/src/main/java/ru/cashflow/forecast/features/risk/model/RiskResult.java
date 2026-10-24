package ru.cashflow.forecast.features.risk.model;

import java.math.BigDecimal;
import java.util.List;

public record RiskResult(RiskEstimate estimate, List<RiskDay> days, List<List<BigDecimal>> scenarios) {
    public RiskResult {
        days = List.copyOf(days);
        scenarios = scenarios.stream().map(List::copyOf).toList();
    }
}
