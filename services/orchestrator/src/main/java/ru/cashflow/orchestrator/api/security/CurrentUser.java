package ru.cashflow.orchestrator.api.security;

import java.util.UUID;

/** Built only from the canonical header set by Gateway; never from request bodies or query parameters. */
public record CurrentUser(UUID userId) {
}
