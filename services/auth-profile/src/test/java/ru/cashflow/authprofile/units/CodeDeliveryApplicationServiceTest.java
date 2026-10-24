package ru.cashflow.authprofile.units;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import ru.cashflow.authprofile.exception.DependencyUnavailableException;
import ru.cashflow.authprofile.features.security.application.service.impl.CodeDeliveryApplicationServiceImpl;
import ru.cashflow.authprofile.features.security.command.model.PendingCode;
import ru.cashflow.authprofile.features.security.command.model.RequestCodeData;
import ru.cashflow.authprofile.features.security.command.service.CodeCommandService;
import ru.cashflow.authprofile.features.security.gateway.CodeDeliveryGateway;

import java.time.Instant;
import java.util.UUID;

class CodeDeliveryApplicationServiceTest {
    @Test
    void failedDeliveryInvalidatesCommittedChallenge() {
        // given
        var commands = mock(CodeCommandService.class);
        var gateway = mock(CodeDeliveryGateway.class);
        var service = new CodeDeliveryApplicationServiceImpl(commands, gateway);
        var request = RequestCodeData.builder().email("user@example.test").source("source").build();
        var pending =
                PendingCode.builder()
                        .id(UUID.randomUUID())
                        .email(request.email())
                        .code("123456")
                        .expiresAt(Instant.MAX)
                        .resendAt(Instant.MAX)
                        .build();
        var failure = new DependencyUnavailableException();
        when(commands.create(request)).thenReturn(pending);
        doThrow(failure).when(gateway).send(pending.email(), pending.code());
        // when / then
        assertThatThrownBy(() -> service.request(request)).isSameAs(failure);
        var order = inOrder(commands, gateway);
        order.verify(commands).create(request);
        order.verify(gateway).send(pending.email(), pending.code());
        order.verify(commands).cancel(pending.id());
    }
}
