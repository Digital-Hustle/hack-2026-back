package ru.cashflow.orchestrator.features.companies.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Proof that a user's current membership was checked. Repositories accept this instead of a raw companyId,
 * so tenant filtering cannot be forgotten or satisfied by a path parameter alone.
 * Only CompanyAccess may construct it; ArchitectureTest enforces that.
 */
public final class CompanyScope {

    private final UUID companyId;
    private final UUID userId;
    private final Role role;

    public CompanyScope(UUID companyId, UUID userId, Role role) {
        this.companyId = Objects.requireNonNull(companyId);
        this.userId = Objects.requireNonNull(userId);
        this.role = Objects.requireNonNull(role);
    }

    public UUID companyId() {
        return companyId;
    }

    public UUID userId() {
        return userId;
    }

    public Role role() {
        return role;
    }
}
