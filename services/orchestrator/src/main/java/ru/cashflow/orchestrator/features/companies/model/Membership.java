package ru.cashflow.orchestrator.features.companies.model;

import java.time.Instant;
import java.util.UUID;

public record Membership(UUID companyId, UUID userId, Role role, long version, Instant joinedAt) {
}
