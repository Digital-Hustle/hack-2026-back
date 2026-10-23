package ru.cashflow.authprofile.units;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import ru.cashflow.authprofile.config.AuthPropertiesFixture;
import ru.cashflow.authprofile.features.security.command.repository.SessionRepository;
import ru.cashflow.authprofile.features.security.command.service.impl.SessionCommandServiceImpl;
import ru.cashflow.authprofile.features.security.command.validator.RefreshSessionValidator;
import ru.cashflow.authprofile.features.security.exceptions.InvalidRefreshException;
import ru.cashflow.authprofile.features.security.exceptions.RefreshReusedException;
import ru.cashflow.authprofile.features.security.infrastructure.service.CredentialSecrets;
import ru.cashflow.authprofile.features.security.infrastructure.service.JwtTokenProvider;
import ru.cashflow.authprofile.features.security.model.RefreshCredential;
import ru.cashflow.authprofile.features.security.model.UserSession;
import ru.cashflow.authprofile.util.time.ApplicationClock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

class SessionCommandServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final String REFRESH = "a".repeat(43);
    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final SessionRepository repository = mock(SessionRepository.class);
    private final CredentialSecrets secrets = mock(CredentialSecrets.class);
    private final JwtTokenProvider tokens = mock(JwtTokenProvider.class);
    private final SessionCommandServiceImpl service =
            new SessionCommandServiceImpl(
                    repository,
                    new RefreshSessionValidator(),
                    secrets,
                    tokens,
                    AuthPropertiesFixture.defaults(),
                    new ApplicationClock(Clock.fixed(NOW, ZoneOffset.UTC)));

    @Test
    void accessJwtIsRejectedBeforeRepositoryLookup() {
        // given / when / then
        assertThatThrownBy(() -> service.rotate("header.payload.signature"))
                .isInstanceOf(InvalidRefreshException.class);
        verifyNoInteractions(repository, secrets, tokens);
    }

    @Test
    void credentialIsRecheckedAfterAcquiringFamilyLock() {
        // given: another transaction consumes the credential between the initial read and lock.
        when(secrets.refreshHash(REFRESH)).thenReturn("presented-hash");
        when(repository.findCredential("presented-hash"))
                .thenReturn(
                        Optional.of(
                                RefreshCredential.builder()
                                        .sessionId(sessionId)
                                        .usedAt(null)
                                        .build()))
                .thenReturn(
                        Optional.of(
                                RefreshCredential.builder()
                                        .sessionId(sessionId)
                                        .usedAt(NOW.minusSeconds(1))
                                        .build()));
        when(repository.findForUpdate(sessionId)).thenReturn(Optional.of(activeSession()));
        // when / then
        assertThatThrownBy(() -> service.rotate(REFRESH))
                .isInstanceOf(RefreshReusedException.class);
        verify(repository).revoke(sessionId, userId, NOW);
        verify(repository, never()).useCredential(any(), any());
        verifyNoInteractions(tokens);
    }

    @Test
    void rotationPreservesSessionAndReplacesCredential() {
        // given
        when(secrets.refreshHash(REFRESH)).thenReturn("presented-hash");
        when(repository.findCredential("presented-hash"))
                .thenReturn(
                        Optional.of(
                                RefreshCredential.builder()
                                        .sessionId(sessionId)
                                        .usedAt(null)
                                        .build()));
        when(repository.findForUpdate(sessionId)).thenReturn(Optional.of(activeSession()));
        String replacement = "b".repeat(43);
        when(secrets.randomToken()).thenReturn(replacement);
        when(secrets.refreshHash(replacement)).thenReturn("replacement-hash");
        when(tokens.createAccessToken(any(), any(), any(), any())).thenReturn("signed-access");
        // when
        var result = service.rotate(REFRESH);
        // then
        assertThat(result.sessionId()).isEqualTo(sessionId);
        assertThat(result.refreshToken()).isEqualTo(replacement);
        verify(repository).useCredential("presented-hash", NOW);
        verify(repository).addCredential(sessionId, "replacement-hash", NOW);
        verify(repository).extend(sessionId, NOW, result.refreshExpiresAt());
    }

    private UserSession activeSession() {
        return UserSession.builder()
                .id(sessionId)
                .userId(userId)
                .createdAt(NOW.minusSeconds(60))
                .lastSeenAt(NOW.minusSeconds(10))
                .expiresAt(NOW.plusSeconds(600))
                .revokedAt(null)
                .build();
    }
}
