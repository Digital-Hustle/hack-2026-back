package ru.cashflow.authprofile.features.security.command.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record IssuedTokens(
        UUID userId,
        UUID sessionId,
        String accessToken,
        String refreshToken,
        Instant accessExpiresAt,
        Instant refreshExpiresAt) {

    @Override
    public String toString() {
        return "IssuedTokens[redacted]";
    }

    public static class IssuedTokensBuilder {

        @Override
        public String toString() {
            return "IssuedTokensBuilder[redacted]";
        }
    }
}
