package ru.cashflow.forecast.features.forecast.model;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ResidualHistory(Map<LocalDate, Flows> days, Set<UUID> excludedPaymentIds) {
    public ResidualHistory {
        days = Map.copyOf(days);
        excludedPaymentIds = Set.copyOf(excludedPaymentIds);
    }
}
