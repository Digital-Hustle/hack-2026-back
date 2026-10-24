package ru.zuzex.practice.gatewayserver.filter;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.Locale;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class DownstreamRequest {

    static final String ACCESS_COOKIE = "__Host-cashflow-access";
    static final String CSRF_COOKIE = "__Host-cashflow-csrf";
    static final String REFRESH_COOKIE = "__Secure-cashflow-refresh";

    static ServerHttpRequest.Builder sanitized(ServerHttpRequest request) {
        return request.mutate().headers(headers -> {
            headers.keySet().removeIf(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.startsWith("x-user-") || lower.startsWith("x-auth-");
            });
            headers.remove(HttpHeaders.AUTHORIZATION);
            headers.remove(HttpHeaders.COOKIE);
        });
    }
}
