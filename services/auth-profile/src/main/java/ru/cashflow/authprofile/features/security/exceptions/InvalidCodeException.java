package ru.cashflow.authprofile.features.security.exceptions;

import ru.cashflow.authprofile.exception.DomainException;

public class InvalidCodeException extends DomainException {

    public InvalidCodeException() {
        super("Invalid code");
    }
}
