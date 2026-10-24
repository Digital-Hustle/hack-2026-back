package ru.cashflow.forecast.features.shared.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Calculation fields only; presentation texts and source documents stay at the HTTP boundary. */
public record KnownPlan(UUID id, UUID companyId, UUID accountId, Direction direction, BigDecimal amount,
        LocalDate dueDate, String categoryCode, String counterpartyKey, PlanRelation relation,
        UUID recurringPatternId, String baselineCounterpartyKey, PlanStatus status,
        BigDecimal settledAmount, BigDecimal remainingAmount, boolean overdue, Instant knownAt,
        Instant createdAt, Instant updatedAt, long version) { }
