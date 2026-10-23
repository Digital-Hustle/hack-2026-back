package ru.cashflow.authprofile.features.security.exceptions;

import ru.cashflow.authprofile.exception.DomainException;

public class ExpiredCodeException extends DomainException {

    public ExpiredCodeException() {
        super("Code expired");
    }
}
