package ru.cashflow.authprofile.units;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import ru.cashflow.authprofile.config.AuthPropertiesFixture;
import ru.cashflow.authprofile.exception.DependencyUnavailableException;
import ru.cashflow.authprofile.features.security.gateway.impl.SmtpCodeDeliveryGateway;

class SmtpCodeDeliveryGatewayTest {
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final SmtpCodeDeliveryGateway gateway =
            new SmtpCodeDeliveryGateway(sender, AuthPropertiesFixture.defaults());

    @Test
    void formatsRecipientAndCodeInsideAdapter() {
        // given / when
        gateway.send("user@example.test", "123456");
        // then
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactly("user@example.test");
        assertThat(message.getValue().getFrom()).isEqualTo("auth@example.test");
        assertThat(message.getValue().getText()).contains("123456");
    }

    @Test
    void providerFailureBecomesSystemException() {
        // given
        var providerFailure = new MailSendException("unavailable");
        doThrow(providerFailure).when(sender).send(any(SimpleMailMessage.class));
        // when / then
        assertThatThrownBy(() -> gateway.send("user@example.test", "123456"))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasCause(providerFailure);
    }
}
