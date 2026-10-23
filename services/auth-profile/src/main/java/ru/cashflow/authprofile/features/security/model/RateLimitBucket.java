package ru.cashflow.authprofile.features.security.model;

import lombok.Builder;

import java.time.Instant;

@Builder
public record RateLimitBucket(
        String bucketKey, Instant windowEnd, int used, Instant nextAllowedAt) {}
