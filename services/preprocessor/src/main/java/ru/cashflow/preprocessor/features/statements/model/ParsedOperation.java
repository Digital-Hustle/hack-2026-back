package ru.cashflow.preprocessor.features.statements.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record ParsedOperation(String companyId, String operationId, LocalDate operationDate, BigDecimal amount,
                              Direction direction, String counterpartyInn, String counterpartyName,
                              String paymentPurpose, BigDecimal balanceEod, CategoryDecision category,
                              long sourceRecord, long sourceLine, Map<String, String> sourceFields) {}
