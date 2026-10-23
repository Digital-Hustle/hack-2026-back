package ru.cashflow.authprofile.features.security.command.service;

import ru.cashflow.authprofile.features.security.command.model.PendingCode;
import ru.cashflow.authprofile.features.security.command.model.RequestCodeData;
import ru.cashflow.authprofile.features.security.command.model.VerifyCodeData;

import java.util.UUID;

public interface CodeCommandService {

    PendingCode create(RequestCodeData data);

    String consume(VerifyCodeData data);

    void cancel(UUID challengeId);
}
