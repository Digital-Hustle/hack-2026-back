package ru.cashflow.authprofile.features.security.infrastructure.service;

import java.time.Instant;
import java.util.UUID;

public interface JwtTokenProvider {

    String createAccessToken(UUID userId, UUID sessionId, Instant now, Instant expiresAt);
}
