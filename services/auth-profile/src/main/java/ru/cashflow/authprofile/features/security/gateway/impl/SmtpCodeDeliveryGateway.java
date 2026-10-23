package ru.cashflow.authprofile.features.security.gateway.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import ru.cashflow.authprofile.config.properties.AuthProperties;
import ru.cashflow.authprofile.exception.DependencyUnavailableException;
import ru.cashflow.authprofile.features.security.gateway.CodeDeliveryGateway;

@Component
@RequiredArgsConstructor
public class SmtpCodeDeliveryGateway implements CodeDeliveryGateway {

    private final JavaMailSender sender;
    private final AuthProperties properties;

    @Override
    public void send(String email, String code) {
        var message = new SimpleMailMessage();
        message.setFrom(properties.mailFrom());
        message.setTo(email);
        message.setSubject("Cashflow: код входа");
        message.setText(
                "Код входа: " + code + "\nЕсли вы не запрашивали вход, проигнорируйте письмо.");
        try {
            sender.send(message);
        } catch (MailException exception) {
            throw new DependencyUnavailableException(exception);
        }
    }
}
