package ru.cashflow.authprofile.features.security.command.service;

import ru.cashflow.authprofile.features.security.command.model.IssuedTokens;

import java.util.UUID;

public interface SessionCommandService {

    IssuedTokens create(UUID userId);

    IssuedTokens rotate(String refreshToken);

    void logout(String refreshToken);

    void revoke(UUID sessionId, UUID userId);

    void revokeAll(UUID userId);
}
