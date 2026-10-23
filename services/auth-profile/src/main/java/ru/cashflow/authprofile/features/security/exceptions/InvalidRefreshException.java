package ru.cashflow.authprofile.features.security.exceptions;

import ru.cashflow.authprofile.exception.DomainException;

public class InvalidRefreshException extends DomainException {

    public InvalidRefreshException() {
        super("Invalid refresh credential");
    }
}
