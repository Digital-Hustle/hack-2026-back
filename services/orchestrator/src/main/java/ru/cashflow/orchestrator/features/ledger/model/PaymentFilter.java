package ru.cashflow.orchestrator.features.ledger.model;

import java.time.LocalDate;

/** Filters of the public payment list; every null field is simply not applied. */
public record PaymentFilter(LocalDate fromDate, LocalDate toDate, LedgerTypes.Direction direction,
                            String categoryCode, String counterpartyKey, String q, boolean ascending) {

    public static PaymentFilter of(LocalDate fromDate, LocalDate toDate, LedgerTypes.Direction direction,
                                   String categoryCode, String counterpartyKey, String q, String sort) {
        boolean ascending = "operationDate,asc".equals(sort);
        return new PaymentFilter(fromDate, toDate, direction, blankToNull(categoryCode),
                blankToNull(counterpartyKey), blankToNull(q), ascending);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}