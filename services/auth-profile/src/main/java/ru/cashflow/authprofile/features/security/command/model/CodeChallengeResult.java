package ru.cashflow.authprofile.features.security.command.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record CodeChallengeResult(UUID challengeId, Instant expiresAt, Instant resendAvailableAt) {}
