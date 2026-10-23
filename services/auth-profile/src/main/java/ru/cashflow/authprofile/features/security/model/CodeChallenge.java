package ru.cashflow.authprofile.features.security.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record CodeChallenge(
        UUID id,
        String email,
        String codeHash,
        int attempts,
        Instant expiresAt,
        Instant consumedAt) {}
