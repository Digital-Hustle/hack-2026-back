package ru.cashflow.authprofile.api.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record AuthSessionRs(
        UUID sessionId, Instant accessExpiresAt, Instant refreshExpiresAt, ProfileRs user) {}
