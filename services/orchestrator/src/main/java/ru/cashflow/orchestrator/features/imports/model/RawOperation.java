package ru.cashflow.orchestrator.features.imports.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;

/** Source CSV values, without an authorization decision or a database account. */
public record RawOperation(String sourceCompanyId, String externalOperationId, LocalDate operationDate,
                           BigDecimal amount, Direction direction, String counterpartyInn,
                           String counterpartyName, String purpose, BigDecimal balanceEod) {
}
