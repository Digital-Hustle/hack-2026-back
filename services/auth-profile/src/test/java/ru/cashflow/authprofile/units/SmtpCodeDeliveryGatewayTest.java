package ru.cashflow.authprofile.units;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import ru.cashflow.authprofile.config.AuthPropertiesFixture;
import ru.cashflow.authprofile.exception.DependencyUnavailableException;
import ru.cashflow.authprofile.features.security.gateway.impl.SmtpCodeDeliveryGateway;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

class SmtpCodeDeliveryGatewayTest {
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final Session session = Session.getInstance(new Properties());
    private final SmtpCodeDeliveryGateway gateway =
            new SmtpCodeDeliveryGateway(sender, AuthPropertiesFixture.defaults());

    @BeforeEach
    void createRealMimeMessage() {
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(session));
    }

    @Test
    void deliversUtf8HtmlAndPlainTextWithSelfContainedInlineImages() throws Exception {
        gateway.send("user@example.test", "012345");

        var message = deliveredMessage();
        assertThat(message.getAllRecipients()).extracting(Object::toString)
                .containsExactly("user@example.test");
        assertThat(message.getFrom()).extracting(Object::toString)
                .containsExactly("auth@example.test");
        assertThat(message.getSubject()).isEqualTo("Код входа в ПрогноCIк");
        assertThat(message.isMimeType("multipart/related")).isTrue();

        var parts = leafParts(message);
        var plain = textPart(parts, "text/plain");
        var html = textPart(parts, "text/html");
        assertThat(plain).contains("012345", "Действует 5 мин", "не запрашивали вход");
        assertThat(html).contains("012345", "Действует 5 мин", "cid:brand-lockup",
                "cid:hero-title", "cid:hero-chart", "Подтвердите вход");
        assertThat(html).doesNotContain("{{", "../", "<script", "<link", "src=\"http");
        assertThat(html.split("012345", -1)).hasSize(2);
        assertThat(parts).hasSize(5);
        for (var part : parts) {
            if (part.isMimeType("image/png")) {
                assertThat(part.getDisposition()).isEqualTo(Part.INLINE);
                var cid = part.getHeader("Content-ID")[0];
                assertThat(html).contains("cid:" + cid.substring(1, cid.length() - 1));
                assertThat(part.getInputStream().readNBytes(8))
                        .containsExactly((byte) 137, (byte) 80, (byte) 78, (byte) 71,
                                (byte) 13, (byte) 10, (byte) 26, (byte) 10);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"PT1M,1 мин", "PT90S,1 мин 30 с", "PT45S,45 с", "PT10M,10 мин"})
    void usesConfiguredLifetimeInBothAlternatives(String configured, String expected)
            throws Exception {
        var properties = AuthPropertiesFixture.defaults(Duration.parse(configured));
        new SmtpCodeDeliveryGateway(sender, properties).send("user@example.test", "123456");

        var parts = leafParts(deliveredMessage());
        assertThat(textPart(parts, "text/plain")).contains("Действует " + expected);
        assertThat(textPart(parts, "text/html")).contains("Действует " + expected);
    }

    @Test
    void providerFailureBecomesSystemException() {
        // given
        var providerFailure = new MailSendException("unavailable");
        doThrow(providerFailure).when(sender).send(any(MimeMessage.class));
        // when / then
        assertThatThrownBy(() -> gateway.send("user@example.test", "123456"))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasCause(providerFailure);
    }

    @Test
    void mimeFailureBecomesSystemException() throws Exception {
        var brokenMessage = mock(MimeMessage.class);
        var failure = new MessagingException("cannot encode subject");
        doThrow(failure).when(brokenMessage).setSubject(anyString(), anyString());
        when(sender.createMimeMessage()).thenReturn(brokenMessage);
        assertThatThrownBy(() -> gateway.send("user@example.test", "123456"))
                .isInstanceOf(DependencyUnavailableException.class)
                .hasCause(failure);
    }

    @ParameterizedTest
    @CsvSource({"12345", "1234567", "<code>", "１２３４５６"})
    void rejectsMalformedCodeWithoutIncludingItsValueInTheError(String code) {
        assertThatThrownBy(() -> gateway.send("user@example.test", code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(code);
    }

    private MimeMessage deliveredMessage() throws Exception {
        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        var bytes = new ByteArrayOutputStream();
        captor.getValue().writeTo(bytes);
        return new MimeMessage(session, new ByteArrayInputStream(bytes.toByteArray()));
    }

    private List<Part> leafParts(Part part) throws Exception {
        var result = new ArrayList<Part>();
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                result.addAll(leafParts(multipart.getBodyPart(i)));
            }
        } else {
            result.add(part);
        }
        return result;
    }

    private String textPart(List<Part> parts, String mimeType) throws Exception {
        for (var part : parts) {
            if (part.isMimeType(mimeType)) {
                assertThat(part.getContentType()).containsIgnoringCase("charset=UTF-8");
                return (String) part.getContent();
            }
        }
        throw new AssertionError("Missing mail alternative: " + mimeType);
    }
}
