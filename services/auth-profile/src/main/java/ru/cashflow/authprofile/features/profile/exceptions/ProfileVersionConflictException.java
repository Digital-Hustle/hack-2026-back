package ru.cashflow.authprofile.features.profile.exceptions;

import ru.cashflow.authprofile.exception.DomainException;

public class ProfileVersionConflictException extends DomainException {

    public ProfileVersionConflictException() {
        super("Profile version conflict");
    }
}
