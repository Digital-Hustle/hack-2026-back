package ru.cashflow.preprocessor.api.dto.response;

import java.time.LocalDate;
import java.util.Map;

import ru.cashflow.preprocessor.features.statements.model.Direction;

public record OperationRs(String companyId, String operationId, LocalDate operationDate, String amount,
                          Direction direction, String counterpartyInn, String counterpartyName, String paymentPurpose,
                          String balanceEod, CategoryRs category, long sourceRecord, long sourceLine,
                          Map<String, String> sourceFields) {}
