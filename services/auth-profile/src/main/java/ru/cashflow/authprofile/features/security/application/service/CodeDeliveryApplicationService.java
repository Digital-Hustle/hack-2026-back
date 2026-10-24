package ru.cashflow.authprofile.features.security.application.service;

import ru.cashflow.authprofile.features.security.command.model.CodeChallengeResult;
import ru.cashflow.authprofile.features.security.command.model.RequestCodeData;

public interface CodeDeliveryApplicationService {

    CodeChallengeResult request(RequestCodeData data);
}
