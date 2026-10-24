package ru.cashflow.authprofile.config.properties;

import static ru.cashflow.authprofile.features.security.constants.SecurityConstants.MAX_CONFIGURED_CODE_ATTEMPTS;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import lombok.Builder;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

@Validated
@ConfigurationProperties("auth")
@Builder
public record AuthProperties(
        @NotEmpty List<@NotBlank String> allowedOrigins,
        @NotBlank String otpHmacSecret,
        @NotBlank @Email String mailFrom,
        @NotNull Duration codeTtl,
        @NotNull Duration resendDelay,
        @Min(1) @Max(MAX_CONFIGURED_CODE_ATTEMPTS) int maxCodeAttempts,
        @NotNull Duration emailWindow,
        @Min(1) int emailLimit,
        @NotNull Duration sourceWindow,
        @Min(1) int sourceLimit,
        @Min(1) int verifySourceLimit,
        @NotNull Duration accessTtl,
        @NotNull Duration refreshTtl,
        @NotNull Duration csrfTtl,
        @Valid @NotNull Jwt jwt) {

    @Builder
    public record Jwt(
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotBlank String keyId,
            @NotBlank String privateKey,
            @NotBlank String publicKey) {

        @Override
        public String toString() {
            return "Jwt[redacted]";
        }

        public static class JwtBuilder {

            @Override
            public String toString() {
                return "JwtBuilder[redacted]";
            }
        }
    }

    @AssertTrue(message = "Auth durations must be positive and resend delay must be below code TTL")
    public boolean isDurationConfigurationValid() {
        return Stream.of(
                                codeTtl,
                                resendDelay,
                                emailWindow,
                                sourceWindow,
                                accessTtl,
                                refreshTtl,
                                csrfTtl)
                        .allMatch(d -> d != null && d.compareTo(Duration.ofSeconds(1)) >= 0)
                && resendDelay.compareTo(codeTtl) < 0;
    }

    @AssertTrue(
            message =
                    "Allowed origins must be exact HTTPS origins without path, query, or userinfo")
    public boolean isOriginConfigurationValid() {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            return false;
        }

        return allowedOrigins.stream()
                .allMatch(
                        value -> {
                            try {
                                URI uri = URI.create(value);

                                return "https".equals(uri.getScheme())
                                        && uri.getHost() != null
                                        && uri.getRawUserInfo() == null
                                        && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                                        && uri.getRawQuery() == null
                                        && uri.getRawFragment() == null;
                            } catch (IllegalArgumentException exception) {
                                return false;
                            }
                        });
    }

    // Configuration must not leak key material through generated record toString().
    @Override
    public String toString() {
        return "AuthProperties[redacted]";
    }

    public static class AuthPropertiesBuilder {

        @Override
        public String toString() {
            return "AuthPropertiesBuilder[redacted]";
        }
    }
}
