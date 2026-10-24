package ru.cashflow.authprofile.features.security.command.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.authprofile.config.properties.AuthProperties;
import ru.cashflow.authprofile.exception.DomainException;
import ru.cashflow.authprofile.features.security.command.model.PendingCode;
import ru.cashflow.authprofile.features.security.command.model.RequestCodeData;
import ru.cashflow.authprofile.features.security.command.model.VerifyCodeData;
import ru.cashflow.authprofile.features.security.command.repository.CodeRepository;
import ru.cashflow.authprofile.features.security.command.service.CodeCommandService;
import ru.cashflow.authprofile.features.security.command.service.RateLimitCommandService;
import ru.cashflow.authprofile.features.security.command.validator.CodeChallengeValidator;
import ru.cashflow.authprofile.features.security.exceptions.InvalidCodeException;
import ru.cashflow.authprofile.features.security.infrastructure.service.CredentialSecrets;
import ru.cashflow.authprofile.util.time.ApplicationClock;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CodeCommandServiceImpl implements CodeCommandService {

    private final CodeRepository codes;
    private final RateLimitCommandService limits;
    private final CodeChallengeValidator validator;
    private final CredentialSecrets secrets;
    private final AuthProperties properties;
    private final ApplicationClock clock;

    @Override
    @Transactional(noRollbackFor = DomainException.class)
    public PendingCode create(RequestCodeData data) {
        String email = data.email().toLowerCase(Locale.ROOT);
        Instant now = clock.now();

        limits.consume(
                secrets.hmac("email-rate", email),
                properties.emailLimit(),
                properties.emailWindow(),
                properties.resendDelay(),
                now);
        limits.consume(
                secrets.hmac("send-source-rate", data.source()),
                properties.sourceLimit(),
                properties.sourceWindow(),
                Duration.ZERO,
                now);

        UUID id = UUID.randomUUID();
        String code = secrets.code();
        Instant expiresAt = now.plus(properties.codeTtl());
        codes.create(id, email, secrets.hmac("otp", id + ":" + code), now, expiresAt);

        return PendingCode.builder()
                .id(id)
                .email(email)
                .code(code)
                .expiresAt(expiresAt)
                .resendAt(now.plus(properties.resendDelay()))
                .build();
    }

    @Override
    @Transactional(noRollbackFor = DomainException.class)
    public String consume(VerifyCodeData data) {
        Instant now = clock.now();

        limits.consume(
                secrets.hmac("verify-source-rate", data.source()),
                properties.verifySourceLimit(),
                properties.sourceWindow(),
                Duration.ZERO,
                now);

        var challenge =
                codes.findForUpdate(data.challengeId()).orElseThrow(InvalidCodeException::new);
        validator.validateUsable(challenge, now);

        if (!secrets.matches(
                challenge.codeHash(), secrets.hmac("otp", challenge.id() + ":" + data.code()))) {
            codes.incrementAttempts(challenge.id());
            validator.validateAttempts(challenge.attempts() + 1, challenge.expiresAt(), now);
            throw new InvalidCodeException();
        }

        codes.consume(challenge.id(), now);

        return challenge.email();
    }

    @Override
    @Transactional
    public void cancel(UUID challengeId) {
        codes.consume(challengeId, clock.now());
    }
}
