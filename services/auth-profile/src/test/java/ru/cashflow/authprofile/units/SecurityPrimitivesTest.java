package ru.cashflow.authprofile.units;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import ru.cashflow.authprofile.api.cookie.AuthCookieManager;
import ru.cashflow.authprofile.api.security.CsrfRequestGuard;
import ru.cashflow.authprofile.config.ApplicationConfig;
import ru.cashflow.authprofile.config.properties.AuthProperties;
import ru.cashflow.authprofile.exception.CsrfInvalidException;
import ru.cashflow.authprofile.features.security.infrastructure.service.impl.CredentialSecretsImpl;
import ru.cashflow.authprofile.util.time.ApplicationClock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

class SecurityPrimitivesTest {
    private static final Instant NOW = Instant.parse("2026-10-24T09:00:00Z");
    private final AuthProperties properties =
            ru.cashflow.authprofile.config.AuthPropertiesFixture.defaults();

    @Test
    void csrfRejectsForgedCookieAndHeaderFromADifferentBinding() {
        var secrets = new CredentialSecretsImpl(new ApplicationConfig().otpHmacKey(properties));
        var csrf =
                new CsrfRequestGuard(
                        secrets,
                        properties,
                        new ApplicationClock(Clock.fixed(NOW, ZoneOffset.UTC)));
        var a = csrf.bootstrap(request());
        var b = csrf.bootstrap(request());
        var valid = request();
        valid.setCookies(new Cookie(AuthCookieManager.CSRF, a.cookie()));
        valid.addHeader("X-CSRF-Token", a.header());
        assertThatCode(() -> csrf.validate(valid)).doesNotThrowAnyException();
        valid.setCookies(new Cookie(AuthCookieManager.CSRF, b.cookie()));
        assertThatThrownBy(() -> csrf.validate(valid)).isInstanceOf(CsrfInvalidException.class);
        valid.setCookies(
                new Cookie(
                        AuthCookieManager.CSRF,
                        a.cookie().substring(0, a.cookie().length() - 1) + "x"));
        assertThatThrownBy(() -> csrf.validate(valid)).isInstanceOf(CsrfInvalidException.class);
    }

    @Test
    void expiredCsrfCannotBeReusedAndFreshBootstrapReplacesIt() {
        var secrets = new CredentialSecretsImpl(new ApplicationConfig().otpHmacKey(properties));
        var csrf =
                new CsrfRequestGuard(
                        secrets,
                        properties,
                        new ApplicationClock(Clock.fixed(NOW, ZoneOffset.UTC)));
        var binding = csrf.bootstrap(request());
        var later =
                new CsrfRequestGuard(
                        secrets,
                        properties,
                        new ApplicationClock(
                                Clock.fixed(NOW.plus(Duration.ofHours(12)), ZoneOffset.UTC)));
        var request = request();
        request.setCookies(new Cookie(AuthCookieManager.CSRF, binding.cookie()));
        request.addHeader("X-CSRF-Token", binding.header());
        assertThatThrownBy(() -> later.validate(request)).isInstanceOf(CsrfInvalidException.class);
        assertThat(later.bootstrap(request).cookie()).isNotEqualTo(binding.cookie());
    }

    @Test
    void sameOriginBootstrapWithoutOriginRequiresTrustedReferrerAndFetchMetadata() {
        var csrf =
                new CsrfRequestGuard(
                        new CredentialSecretsImpl(new ApplicationConfig().otpHmacKey(properties)),
                        properties,
                        new ApplicationClock(Clock.fixed(NOW, ZoneOffset.UTC)));
        var sameOrigin = new MockHttpServletRequest();
        sameOrigin.addHeader("Sec-Fetch-Site", "same-origin");
        sameOrigin.addHeader("Referer", "https://app.example.test/dashboard");
        assertThat(csrf.bootstrap(sameOrigin).header()).isNotBlank();
        var forged = new MockHttpServletRequest();
        forged.addHeader("Sec-Fetch-Site", "same-origin");
        forged.addHeader("Referer", "https://app.example.test.evil/dashboard");
        assertThatThrownBy(() -> csrf.bootstrap(forged)).isInstanceOf(CsrfInvalidException.class);
        assertThatThrownBy(() -> csrf.validate(sameOrigin))
                .isInstanceOf(CsrfInvalidException.class);
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://app.example.test");
        return request;
    }
}
