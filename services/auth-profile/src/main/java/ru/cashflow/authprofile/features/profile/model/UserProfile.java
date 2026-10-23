package ru.cashflow.authprofile.features.profile.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record UserProfile(
        UUID id,
        String email,
        String displayName,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
