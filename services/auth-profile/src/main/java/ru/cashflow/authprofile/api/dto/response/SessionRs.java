package ru.cashflow.authprofile.api.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record SessionRs(
        UUID id, Instant createdAt, Instant lastSeenAt, Instant expiresAt, boolean current) {}
