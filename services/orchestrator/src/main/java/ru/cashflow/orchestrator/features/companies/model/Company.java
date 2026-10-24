package ru.cashflow.orchestrator.features.companies.model;

import java.time.Instant;
import java.util.UUID;

public record Company(UUID id, String name, LegalForm legalForm, String inn, String kpp, String timezone,
                      UUID ownerUserId, boolean archived, long dataVersion, long version,
                      Instant createdAt, Instant updatedAt) {
}
