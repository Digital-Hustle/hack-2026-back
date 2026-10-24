package ru.cashflow.authprofile.features.security.command.validator;

import static ru.cashflow.authprofile.features.security.constants.SecurityConstants.OPAQUE_TOKEN_PATTERN;

import org.springframework.stereotype.Component;

import ru.cashflow.authprofile.features.security.exceptions.InvalidRefreshException;
import ru.cashflow.authprofile.features.security.model.UserSession;

import java.time.Instant;

@Component
public class RefreshSessionValidator {

    public boolean isOpaqueToken(String token) {
        return token != null && token.matches(OPAQUE_TOKEN_PATTERN);
    }

    public void validateToken(String token) {
        if (!isOpaqueToken(token)) {
            throw new InvalidRefreshException();
        }
    }

    public void validateActive(UserSession session, Instant now) {
        if (!session.active(now)) {
            throw new InvalidRefreshException();
        }
    }
}
