package ru.cashflow.authprofile.features.security.command.model;

import lombok.Builder;

import java.util.UUID;

@Builder
public record VerifyCodeData(UUID challengeId, String code, String source) {

    @Override
    public String toString() {
        return "VerifyCodeData[redacted]";
    }

    public static class VerifyCodeDataBuilder {

        @Override
        public String toString() {
            return "VerifyCodeDataBuilder[redacted]";
        }
    }
}
