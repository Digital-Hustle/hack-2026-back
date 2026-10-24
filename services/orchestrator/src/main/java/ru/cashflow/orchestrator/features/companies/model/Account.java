package ru.cashflow.orchestrator.features.companies.model;

import java.time.Instant;
import java.util.UUID;

public record Account(UUID id, UUID companyId, String name, String bankName, String accountNumber,
                      String externalCompanyId, String currency, boolean archived, long version,
                      Instant createdAt, Instant updatedAt) {
}
