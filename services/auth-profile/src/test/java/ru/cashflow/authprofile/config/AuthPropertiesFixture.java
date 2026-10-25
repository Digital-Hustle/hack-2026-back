package ru.cashflow.authprofile.config;

import ru.cashflow.authprofile.config.properties.AuthProperties;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

public final class AuthPropertiesFixture {
    private AuthPropertiesFixture() {}

    public static AuthProperties defaults() {
        return defaults(Duration.ofMinutes(5));
    }

    public static AuthProperties defaults(Duration codeTtl) {
        byte[] hmac = new byte[32];
        new SecureRandom().nextBytes(hmac);
        return AuthProperties.builder()
                .allowedOrigins(List.of("https://app.example.test"))
                .otpHmacSecret(Base64.getEncoder().encodeToString(hmac))
                .mailFrom("auth@example.test")
                .codeTtl(codeTtl)
                .resendDelay(Duration.ofMinutes(1))
                .maxCodeAttempts(5)
                .emailWindow(Duration.ofHours(1))
                .emailLimit(5)
                .sourceWindow(Duration.ofMinutes(15))
                .sourceLimit(30)
                .verifySourceLimit(100)
                .accessTtl(Duration.ofMinutes(15))
                .refreshTtl(Duration.ofDays(30))
                .csrfTtl(Duration.ofHours(12))
                .jwt(
                        AuthProperties.Jwt.builder()
                                .issuer("https://auth.cashflow.internal")
                                .audience("cashflow-api")
                                .keyId("test")
                                .privateKey("")
                                .publicKey("")
                                .build())
                .build();
    }
}
