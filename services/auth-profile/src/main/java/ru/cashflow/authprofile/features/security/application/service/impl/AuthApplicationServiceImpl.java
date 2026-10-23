package ru.cashflow.authprofile.features.security.application.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.authprofile.features.profile.api.ProfileLookup;
import ru.cashflow.authprofile.features.profile.api.ProfileProvisioner;
import ru.cashflow.authprofile.features.security.application.service.AuthApplicationService;
import ru.cashflow.authprofile.features.security.command.model.AuthenticatedSession;
import ru.cashflow.authprofile.features.security.command.model.VerifyCodeData;
import ru.cashflow.authprofile.features.security.command.service.CodeCommandService;
import ru.cashflow.authprofile.features.security.command.service.SessionCommandService;
import ru.cashflow.authprofile.features.security.command.service.UserCommandService;
import ru.cashflow.authprofile.features.security.exceptions.CodeAttemptsExceededException;
import ru.cashflow.authprofile.features.security.exceptions.ExpiredCodeException;
import ru.cashflow.authprofile.features.security.exceptions.InvalidCodeException;
import ru.cashflow.authprofile.features.security.exceptions.RateLimitedException;
import ru.cashflow.authprofile.features.security.exceptions.RefreshReusedException;

@Service
@RequiredArgsConstructor
public class AuthApplicationServiceImpl implements AuthApplicationService {

    private final CodeCommandService codes;
    private final UserCommandService users;
    private final SessionCommandService sessions;
    private final ProfileProvisioner profiles;
    private final ProfileLookup lookup;

    @Override
    @Transactional(
            noRollbackFor = {
                InvalidCodeException.class,
                ExpiredCodeException.class,
                CodeAttemptsExceededException.class,
                RateLimitedException.class
            })
    public AuthenticatedSession verifyCode(VerifyCodeData data) {
        String email = codes.consume(data);
        var user = users.createIfMissing(email);
        profiles.createIfMissing(user.id(), user.createdAt());

        var tokens = sessions.create(user.id());
        var profile = lookup.getByUserId(user.id());

        return AuthenticatedSession.builder().tokens(tokens).user(profile).build();
    }

    @Override
    @Transactional(noRollbackFor = RefreshReusedException.class)
    public AuthenticatedSession refresh(String refreshToken) {
        var tokens = sessions.rotate(refreshToken);
        var profile = lookup.getByUserId(tokens.userId());

        return AuthenticatedSession.builder().tokens(tokens).user(profile).build();
    }
}
