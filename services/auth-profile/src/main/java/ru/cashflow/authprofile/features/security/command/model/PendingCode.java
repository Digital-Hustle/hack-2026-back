package ru.cashflow.authprofile.features.security.command.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record PendingCode(UUID id, String email, String code, Instant expiresAt, Instant resendAt) {

    @Override
    public String toString() {
        return "PendingCode[redacted]";
    }

    public static class PendingCodeBuilder {

        @Override
        public String toString() {
            return "PendingCodeBuilder[redacted]";
        }
    }
}
