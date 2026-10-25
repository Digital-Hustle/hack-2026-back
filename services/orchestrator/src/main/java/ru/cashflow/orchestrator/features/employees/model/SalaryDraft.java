package ru.cashflow.orchestrator.features.employees.model;

import java.math.BigDecimal;
import java.util.UUID;

import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Schedule;

public record SalaryDraft(UUID accountId, BigDecimal amount, Schedule schedule,
                          String counterpartyInn, String counterpartyName, Long recurringPatternVersion) {
}
