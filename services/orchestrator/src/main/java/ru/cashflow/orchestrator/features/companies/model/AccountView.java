package ru.cashflow.orchestrator.features.companies.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Account plus the latest balance known from payments or a balance anchor. */
public record AccountView(Account account, BigDecimal latestBalance, LocalDate latestBalanceDate) {
}