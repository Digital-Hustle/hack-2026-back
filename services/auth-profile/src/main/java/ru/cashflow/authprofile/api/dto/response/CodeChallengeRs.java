package ru.cashflow.authprofile.api.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record CodeChallengeRs(
        UUID challengeId, Instant expiresAt, Instant resendAvailableAt, int codeLength) {}
