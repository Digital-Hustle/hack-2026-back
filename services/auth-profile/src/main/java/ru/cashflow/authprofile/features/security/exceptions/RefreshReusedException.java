package ru.cashflow.authprofile.features.security.exceptions;

import ru.cashflow.authprofile.exception.DomainException;

public class RefreshReusedException extends DomainException {

    public RefreshReusedException() {
        super("Refresh credential reused");
    }
}
