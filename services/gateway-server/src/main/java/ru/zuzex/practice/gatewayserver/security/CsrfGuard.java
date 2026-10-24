package ru.zuzex.practice.gatewayserver.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Set;

@Component
public class CsrfGuard {

    private static final String CSRF_COOKIE = "__Host-cashflow-csrf";
    private static final String CSRF_HEADER = "X-CSRF-Token";
    private static final Set<HttpMethod> SAFE_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient auth;
    private final List<String> allowedOrigins;

    public CsrfGuard(
            WebClient.Builder builder,
            @Value("${gateway.auth-profile-url}") String authUrl,
            @Value("${gateway.allowed-origins}") List<String> allowedOrigins) {
        auth = builder.baseUrl(authUrl).build();
        this.allowedOrigins = List.copyOf(allowedOrigins);
    }

    public Mono<Void> validate(ServerWebExchange exchange) {
        var request = exchange.getRequest();
        if (SAFE_METHODS.contains(request.getMethod())) {
            return Mono.empty();
        }
        var origins = request.getHeaders().getOrEmpty(HttpHeaders.ORIGIN);
        var headers = request.getHeaders().getOrEmpty(CSRF_HEADER);
        var cookies = request.getCookies().get(CSRF_COOKIE);
        if (origins.size() != 1 || !allowedOrigins.contains(origins.getFirst())
                || headers.size() != 1 || headers.getFirst().isBlank()
                || cookies == null || cookies.size() != 1 || cookies.getFirst().getValue().isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
        }

        // Auth owns the HMAC secret and checks expiry/signature of the CSRF cookie.
        // Bootstrap reuses a valid binding; an invalid cookie produces a fresh,
        // unpredictable token, which cannot match the client's supplied header.
        return auth.get().uri("/api/v1/auth-profile/auth/csrf")
                .header(HttpHeaders.ORIGIN, origins.getFirst())
                .header(HttpHeaders.COOKIE, CSRF_COOKIE + "=" + cookies.getFirst().getValue())
                .exchangeToMono(response -> {
                    if (response.statusCode().is4xxClientError()) {
                        return response.releaseBody().then(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN)));
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().then(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)));
                    }
                    return response.bodyToMono(CsrfBinding.class);
                })
                .timeout(TIMEOUT)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)))
                .flatMap(binding -> {
                    if (binding.token() == null || !CSRF_HEADER.equals(binding.headerName())
                            || !MessageDigest.isEqual(binding.token().getBytes(StandardCharsets.UTF_8),
                            headers.getFirst().getBytes(StandardCharsets.UTF_8))) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
                    }
                    return Mono.<Void>empty();
                })
                .onErrorMap(error -> !(error instanceof ResponseStatusException),
                        error -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
    }

    private record CsrfBinding(String token, String headerName) {

        @Override
        public String toString() {
            return "CsrfBinding[redacted]";
        }
    }
}
