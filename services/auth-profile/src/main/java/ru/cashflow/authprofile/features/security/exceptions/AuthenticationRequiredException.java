package ru.cashflow.authprofile.features.security.exceptions;

import ru.cashflow.authprofile.exception.DomainException;

public class AuthenticationRequiredException extends DomainException {

    public AuthenticationRequiredException() {
        super("Authentication required");
    }
}
