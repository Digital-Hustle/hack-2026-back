package ru.cashflow.authprofile.features.security.exceptions;

import ru.cashflow.authprofile.exception.DomainException;

public class SessionNotFoundException extends DomainException {

    public SessionNotFoundException() {
        super("Session not found");
    }
}
