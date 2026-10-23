package ru.cashflow.authprofile.features.security.query.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record SessionInfo(
        UUID id, Instant createdAt, Instant lastSeenAt, Instant expiresAt, boolean current) {}
