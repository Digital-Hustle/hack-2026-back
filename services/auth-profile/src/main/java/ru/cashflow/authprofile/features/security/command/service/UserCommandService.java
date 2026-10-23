package ru.cashflow.authprofile.features.security.command.service;

import ru.cashflow.authprofile.features.security.model.UserIdentity;

public interface UserCommandService {

    UserIdentity createIfMissing(String email);
}
