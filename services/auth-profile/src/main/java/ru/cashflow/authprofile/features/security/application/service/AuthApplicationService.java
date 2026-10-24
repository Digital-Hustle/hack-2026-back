package ru.cashflow.authprofile.features.security.application.service;

import ru.cashflow.authprofile.features.security.command.model.AuthenticatedSession;
import ru.cashflow.authprofile.features.security.command.model.VerifyCodeData;

public interface AuthApplicationService {

    AuthenticatedSession verifyCode(VerifyCodeData data);

    AuthenticatedSession refresh(String refreshToken);
}
