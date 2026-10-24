package ru.cashflow.authprofile.features.security.command.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.authprofile.config.properties.AuthProperties;
import ru.cashflow.authprofile.features.security.command.model.IssuedTokens;
import ru.cashflow.authprofile.features.security.command.repository.SessionRepository;
import ru.cashflow.authprofile.features.security.command.service.SessionCommandService;
import ru.cashflow.authprofile.features.security.command.validator.RefreshSessionValidator;
import ru.cashflow.authprofile.features.security.exceptions.InvalidRefreshException;
import ru.cashflow.authprofile.features.security.exceptions.RefreshReusedException;
import ru.cashflow.authprofile.features.security.exceptions.SessionNotFoundException;
import ru.cashflow.authprofile.features.security.infrastructure.service.CredentialSecrets;
import ru.cashflow.authprofile.features.security.infrastructure.service.JwtTokenProvider;
import ru.cashflow.authprofile.util.time.ApplicationClock;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionCommandServiceImpl implements SessionCommandService {

    private final SessionRepository sessions;
    private final RefreshSessionValidator validator;
    private final CredentialSecrets secrets;
    private final JwtTokenProvider tokens;
    private final AuthProperties properties;
    private final ApplicationClock clock;

    @Override
    @Transactional
    public IssuedTokens create(UUID userId) {
        Instant now = clock.nowAtSecond();
        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = now.plus(properties.refreshTtl());
        sessions.create(sessionId, userId, now, expiresAt);

        return issue(sessionId, userId, now, expiresAt);
    }

    @Override
    @Transactional(noRollbackFor = RefreshReusedException.class)
    public IssuedTokens rotate(String refreshToken) {
        validator.validateToken(refreshToken);

        String hash = secrets.refreshHash(refreshToken);
        var initial = sessions.findCredential(hash).orElseThrow(InvalidRefreshException::new);
        var session =
                sessions.findForUpdate(initial.sessionId())
                        .orElseThrow(InvalidRefreshException::new);
        Instant now = clock.nowAtSecond();
        validator.validateActive(session, now);

        // Re-read under the family lock: another refresh may already have consumed this credential.
        var credential = sessions.findCredential(hash).orElseThrow(InvalidRefreshException::new);
        if (credential.usedAt() != null) {
            sessions.revoke(session.id(), session.userId(), now);
            throw new RefreshReusedException();
        }

        sessions.useCredential(hash, now);
        Instant expiresAt = now.plus(properties.refreshTtl());
        sessions.extend(session.id(), now, expiresAt);

        return issue(session.id(), session.userId(), now, expiresAt);
    }

    @Override
    @Transactional
    public void logout(String refreshToken) {
        if (!validator.isOpaqueToken(refreshToken)) {
            return;
        }

        sessions.findCredential(secrets.refreshHash(refreshToken))
                .ifPresent(
                        credential ->
                                sessions.findForUpdate(credential.sessionId())
                                        .ifPresent(
                                                session ->
                                                        sessions.revoke(
                                                                session.id(),
                                                                session.userId(),
                                                                clock.nowAtSecond())));
    }

    @Override
    @Transactional
    public void revoke(UUID sessionId, UUID userId) {
        if (!sessions.revoke(sessionId, userId, clock.nowAtSecond())) {
            throw new SessionNotFoundException();
        }
    }

    @Override
    @Transactional
    public void revokeAll(UUID userId) {
        sessions.revokeAll(userId, clock.nowAtSecond());
    }

    private IssuedTokens issue(UUID sessionId, UUID userId, Instant now, Instant refreshExpiresAt) {
        String refresh = secrets.randomToken();
        sessions.addCredential(sessionId, secrets.refreshHash(refresh), now);
        Instant accessExpiresAt = now.plus(properties.accessTtl());

        return IssuedTokens.builder()
                .userId(userId)
                .sessionId(sessionId)
                .accessToken(tokens.createAccessToken(userId, sessionId, now, accessExpiresAt))
                .refreshToken(refresh)
                .accessExpiresAt(accessExpiresAt)
                .refreshExpiresAt(refreshExpiresAt)
                .build();
    }
}
