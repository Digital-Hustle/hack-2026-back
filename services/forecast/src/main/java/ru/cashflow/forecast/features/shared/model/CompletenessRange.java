package ru.cashflow.forecast.features.shared.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CompletenessRange(UUID id, UUID accountId, LocalDate fromDate, LocalDate toDate,
        Completeness completeness, String reason, Instant createdAt) { }
