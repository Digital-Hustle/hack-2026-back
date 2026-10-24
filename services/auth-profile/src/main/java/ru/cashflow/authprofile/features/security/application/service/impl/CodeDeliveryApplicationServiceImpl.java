package ru.cashflow.authprofile.features.security.application.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import ru.cashflow.authprofile.exception.DependencyUnavailableException;
import ru.cashflow.authprofile.features.security.application.service.CodeDeliveryApplicationService;
import ru.cashflow.authprofile.features.security.command.model.CodeChallengeResult;
import ru.cashflow.authprofile.features.security.command.model.RequestCodeData;
import ru.cashflow.authprofile.features.security.command.service.CodeCommandService;
import ru.cashflow.authprofile.features.security.gateway.CodeDeliveryGateway;

@Service
@RequiredArgsConstructor
public class CodeDeliveryApplicationServiceImpl implements CodeDeliveryApplicationService {

    private final CodeCommandService codes;
    private final CodeDeliveryGateway delivery;

    @Override
    public CodeChallengeResult request(RequestCodeData data) {
        var pending = codes.create(data);
        // Delivery is outside the database transaction; ambiguous/failed SMTP invalidates the
        // challenge.
        try {
            delivery.send(pending.email(), pending.code());
        } catch (DependencyUnavailableException exception) {
            codes.cancel(pending.id());
            throw exception;
        }

        return CodeChallengeResult.builder()
                .challengeId(pending.id())
                .expiresAt(pending.expiresAt())
                .resendAvailableAt(pending.resendAt())
                .build();
    }
}
