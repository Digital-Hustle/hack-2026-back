package ru.cashflow.authprofile.api.cookie;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import ru.cashflow.authprofile.api.ApiPaths;

import java.time.Duration;

@Component
public class AuthCookieManager {

    public static final String ACCESS = "__Host-cashflow-access";
    public static final String CSRF = "__Host-cashflow-csrf";

    public String cookie(String name, String value, Duration age) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(ApiPaths.REFRESH_COOKIE.equals(name) ? ApiPaths.AUTH : "/")
                .maxAge(age)
                .build()
                .toString();
    }

    public void setTokens(
            HttpHeaders headers,
            String access,
            String refresh,
            Duration accessAge,
            Duration refreshAge) {
        headers.add(HttpHeaders.SET_COOKIE, cookie(ACCESS, access, accessAge));
        headers.add(HttpHeaders.SET_COOKIE, cookie(ApiPaths.REFRESH_COOKIE, refresh, refreshAge));
    }

    public void clear(HttpHeaders headers, boolean includeCsrf) {
        headers.add(HttpHeaders.SET_COOKIE, cookie(ACCESS, "", Duration.ZERO));
        headers.add(HttpHeaders.SET_COOKIE, cookie(ApiPaths.REFRESH_COOKIE, "", Duration.ZERO));
        if (includeCsrf) {
            headers.add(HttpHeaders.SET_COOKIE, cookie(CSRF, "", Duration.ZERO));
        }
    }
}
