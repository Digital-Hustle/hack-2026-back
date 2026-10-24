package ru.cashflow.authprofile.units;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import ru.cashflow.authprofile.config.AuthPropertiesFixture;
import ru.cashflow.authprofile.features.security.command.validator.CodeChallengeValidator;
import ru.cashflow.authprofile.features.security.exceptions.CodeAttemptsExceededException;
import ru.cashflow.authprofile.features.security.exceptions.ExpiredCodeException;
import ru.cashflow.authprofile.features.security.exceptions.InvalidCodeException;
import ru.cashflow.authprofile.features.security.model.CodeChallenge;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

class CodeChallengeValidatorTest {
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private final CodeChallengeValidator validator =
            new CodeChallengeValidator(AuthPropertiesFixture.defaults());

    @Test
    void consumedChallengeCannotBeUsedAgain() {
        // given
        var challenge = challenge(0, NOW.plusSeconds(60), NOW.minusSeconds(1));
        // when / then
        assertThatThrownBy(() -> validator.validateUsable(challenge, NOW))
                .isInstanceOf(InvalidCodeException.class);
    }

    @Test
    void expiryIsExclusive() {
        // given
        var challenge = challenge(0, NOW, null);
        // when / then
        assertThatThrownBy(() -> validator.validateUsable(challenge, NOW))
                .isInstanceOf(ExpiredCodeException.class);
    }

    @Test
    void lastAllowedFailureBlocksUntilExpiry() {
        // given
        var challenge = challenge(5, NOW.plusSeconds(40), null);
        // when
        var failure =
                assertThrows(
                        CodeAttemptsExceededException.class,
                        () -> validator.validateUsable(challenge, NOW));
        // then
        assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(40));
    }

    private CodeChallenge challenge(int attempts, Instant expiresAt, Instant consumedAt) {
        return CodeChallenge.builder()
                .id(UUID.randomUUID())
                .email("user@example.test")
                .codeHash("hash")
                .attempts(attempts)
                .expiresAt(expiresAt)
                .consumedAt(consumedAt)
                .build();
    }
}
