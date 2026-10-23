package ru.cashflow.authprofile.features.profile.api;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record ProfileSnapshot(
        UUID id,
        String email,
        String displayName,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
