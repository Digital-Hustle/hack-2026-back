package ru.cashflow.authprofile.features.security.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record UserSession(
        UUID id,
        UUID userId,
        Instant createdAt,
        Instant lastSeenAt,
        Instant expiresAt,
        Instant revokedAt) {
    public boolean active(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
