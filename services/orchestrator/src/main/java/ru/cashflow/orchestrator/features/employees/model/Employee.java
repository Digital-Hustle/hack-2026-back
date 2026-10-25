package ru.cashflow.orchestrator.features.employees.model;

import java.time.Instant;
import java.util.UUID;

public record Employee(UUID id, UUID companyId, String fullName, String position, String note,
                       boolean archived, UUID recurringPatternId, long version, Instant createdAt, Instant updatedAt) {
}
