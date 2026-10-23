package ru.cashflow.authprofile.api.security;

import static ru.cashflow.authprofile.features.security.constants.SecurityConstants.OPAQUE_TOKEN_PATTERN;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import lombok.Builder;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import ru.cashflow.authprofile.api.cookie.AuthCookieManager;
import ru.cashflow.authprofile.config.properties.AuthProperties;
import ru.cashflow.authprofile.exception.CsrfInvalidException;
import ru.cashflow.authprofile.features.security.infrastructure.service.CredentialSecrets;
import ru.cashflow.authprofile.util.time.ApplicationClock;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CsrfRequestGuard {

    @Builder
    public record Binding(String cookie, String header, Duration maxAge) {

        @Override
        public String toString() {
            return "Binding[redacted]";
        }

        public static class BindingBuilder {

            @Override
            public String toString() {
                return "BindingBuilder[redacted]";
            }
        }
    }

    private static final int MAX_COOKIE_LENGTH = 160;
    private static final int COOKIE_PART_COUNT = 3;
    private static final String EXPIRY_PATTERN = "[0-9]{1,12}";

    private final CredentialSecrets secrets;
    private final AuthProperties properties;
    private final ApplicationClock clock;

    public Binding bootstrap(HttpServletRequest request) {
        requireOrigin(request, true);
        String cookie = cookie(request, AuthCookieManager.CSRF);
        if (!validCookie(cookie)) {
            String unsigned =
                    clock.now().plus(properties.csrfTtl()).getEpochSecond()
                            + "."
                            + secrets.randomToken();
            cookie = unsigned + "." + secrets.hmac("csrf-cookie", unsigned);
        }
        long expires = Long.parseLong(cookie.substring(0, cookie.indexOf('.')));

        return Binding.builder()
                .cookie(cookie)
                .header(secrets.hmac("csrf-header", cookie))
                .maxAge(Duration.ofSeconds(Math.max(1, expires - clock.now().getEpochSecond())))
                .build();
    }

    public void validate(HttpServletRequest request) {
        requireOrigin(request, false);
        String cookie = cookie(request, AuthCookieManager.CSRF);
        List<String> headers = Collections.list(request.getHeaders("X-CSRF-Token"));
        if (!validCookie(cookie)
                || headers.size() != 1
                || !secrets.matches(secrets.hmac("csrf-header", cookie), headers.getFirst())) {
            invalid();
        }
    }

    private boolean validCookie(String cookie) {
        if (cookie == null || cookie.length() > MAX_COOKIE_LENGTH) {
            return false;
        }
        String[] parts = cookie.split("\\.", -1);
        if (parts.length != COOKIE_PART_COUNT
                || !parts[0].matches(EXPIRY_PATTERN)
                || !parts[1].matches(OPAQUE_TOKEN_PATTERN)) {
            return false;
        }
        try {
            return Long.parseLong(parts[0]) > clock.now().getEpochSecond()
                    && secrets.matches(
                            parts[2], secrets.hmac("csrf-cookie", parts[0] + "." + parts[1]));
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private void requireOrigin(HttpServletRequest request, boolean bootstrap) {
        List<String> origins = Collections.list(request.getHeaders("Origin"));
        if (origins.size() == 1 && properties.allowedOrigins().contains(origins.getFirst())) {
            return;
        }
        // Same-origin browser GETs often omit Origin; Referer is checked against the same exact
        // allowlist.
        if (bootstrap
                && origins.isEmpty()
                && "same-origin".equals(request.getHeader("Sec-Fetch-Site"))) {
            try {
                URI referer = URI.create(request.getHeader("Referer"));
                String origin = referer.getScheme() + "://" + referer.getRawAuthority();
                if (referer.getRawUserInfo() == null
                        && properties.allowedOrigins().contains(origin)) {
                    return;
                }
            } catch (RuntimeException ignored) {
                /* fail closed */
            }
        }
        invalid();
    }

    public static String cookie(HttpServletRequest request, String name) {
        List<Cookie> matches =
                request.getCookies() == null
                        ? List.of()
                        : Arrays.stream(request.getCookies())
                                .filter(c -> name.equals(c.getName()))
                                .toList();
        if (matches.size() > 1) {
            throw new CsrfInvalidException();
        }

        return matches.isEmpty() ? null : matches.getFirst().getValue();
    }

    private void invalid() {
        throw new CsrfInvalidException();
    }
}
