package ru.cashflow.authprofile.features.security.gateway.impl;

import static ru.cashflow.authprofile.features.security.constants.SecurityConstants.OTP_CODE_PATTERN;

import jakarta.mail.MessagingException;

import lombok.RequiredArgsConstructor;

import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import ru.cashflow.authprofile.config.properties.AuthProperties;
import ru.cashflow.authprofile.exception.DependencyUnavailableException;
import ru.cashflow.authprofile.features.security.gateway.CodeDeliveryGateway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
@RequiredArgsConstructor
public class SmtpCodeDeliveryGateway implements CodeDeliveryGateway {

    private static final String RESOURCE_ROOT = "mail/login-code/";
    private static final String HTML_TEMPLATE = readTemplate("message.html");
    private static final String TEXT_TEMPLATE = readTemplate("message.txt");

    private final JavaMailSender sender;
    private final AuthProperties properties;

    @Override
    public void send(String email, String code) {
        if (code == null || !code.matches(OTP_CODE_PATTERN)) {
            throw new IllegalArgumentException("OTP must contain six ASCII digits");
        }
        var lifetime = formatLifetime(properties.codeTtl());
        try {
            var message = sender.createMimeMessage();
            var helper =
                    new MimeMessageHelper(
                            message,
                            MimeMessageHelper.MULTIPART_MODE_RELATED,
                            StandardCharsets.UTF_8.name());
            helper.setFrom(properties.mailFrom());
            helper.setTo(email);
            helper.setSubject("Код входа в ПрогноCIк");
            // Only ASCII digits and a server-formatted duration enter these HTML placeholders.
            helper.setText(
                    render(TEXT_TEMPLATE, code, lifetime), render(HTML_TEMPLATE, code, lifetime));
            helper.addInline(
                    "brand-lockup",
                    new ClassPathResource(RESOURCE_ROOT + "brand-lockup.png"),
                    "image/png");
            helper.addInline(
                    "hero-title", new ClassPathResource(RESOURCE_ROOT + "hero-title.png"), "image/png");
            helper.addInline(
                    "hero-chart", new ClassPathResource(RESOURCE_ROOT + "hero-chart.png"), "image/png");
            sender.send(message);
        } catch (MailException | MessagingException exception) {
            throw new DependencyUnavailableException(exception);
        }
    }

    private static String render(String template, String code, String lifetime) {
        return template.replace("{{code}}", code).replace("{{lifetime}}", lifetime);
    }

    private static String formatLifetime(Duration duration) {
        long minutes = duration.toSeconds() / 60;
        long seconds = duration.toSeconds() % 60;
        if (minutes == 0) {
            return seconds + " с";
        }
        return minutes + " мин" + (seconds == 0 ? "" : " " + seconds + " с");
    }

    private static String readTemplate(String name) {
        try {
            return new ClassPathResource(RESOURCE_ROOT + name)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot load login email template", exception);
        }
    }
}
