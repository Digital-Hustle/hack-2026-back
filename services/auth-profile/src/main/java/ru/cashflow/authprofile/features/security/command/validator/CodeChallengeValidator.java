package ru.cashflow.authprofile.features.security.command.validator;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import ru.cashflow.authprofile.config.properties.AuthProperties;
import ru.cashflow.authprofile.features.security.exceptions.CodeAttemptsExceededException;
import ru.cashflow.authprofile.features.security.exceptions.ExpiredCodeException;
import ru.cashflow.authprofile.features.security.exceptions.InvalidCodeException;
import ru.cashflow.authprofile.features.security.model.CodeChallenge;

import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class CodeChallengeValidator {

    private final AuthProperties properties;

    public void validateUsable(CodeChallenge challenge, Instant now) {
        if (challenge.consumedAt() != null) {
            throw new InvalidCodeException();
        }
        if (!challenge.expiresAt().isAfter(now)) {
            throw new ExpiredCodeException();
        }
        validateAttempts(challenge.attempts(), challenge.expiresAt(), now);
    }

    public void validateAttempts(int attempts, Instant expiresAt, Instant now) {
        if (attempts >= properties.maxCodeAttempts()) {
            throw new CodeAttemptsExceededException(Duration.between(now, expiresAt));
        }
    }
}
