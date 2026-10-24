package ru.cashflow.authprofile.features.security.command.model;

import lombok.Builder;

import ru.cashflow.authprofile.features.profile.api.ProfileSnapshot;

@Builder
public record AuthenticatedSession(IssuedTokens tokens, ProfileSnapshot user) {

    @Override
    public String toString() {
        return "AuthenticatedSession[redacted]";
    }

    public static class AuthenticatedSessionBuilder {

        @Override
        public String toString() {
            return "AuthenticatedSessionBuilder[redacted]";
        }
    }
}
