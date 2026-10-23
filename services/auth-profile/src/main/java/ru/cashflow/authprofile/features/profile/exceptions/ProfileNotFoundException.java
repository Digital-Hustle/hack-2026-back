package ru.cashflow.authprofile.features.profile.exceptions;

import ru.cashflow.authprofile.exception.DomainException;

public class ProfileNotFoundException extends DomainException {

    public ProfileNotFoundException() {
        super("Profile not found");
    }
}
