package ru.cashflow.authprofile.api.security;

import lombok.Builder;

import java.util.UUID;

/** Populated from gateway context by the service security filter, never from request JSON. */
@Builder
public record CurrentUser(UUID userId, UUID sessionId) {}
