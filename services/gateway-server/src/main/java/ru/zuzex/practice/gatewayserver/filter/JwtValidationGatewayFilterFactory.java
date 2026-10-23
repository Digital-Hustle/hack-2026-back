package ru.zuzex.practice.gatewayserver.filter;

import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import ru.zuzex.practice.gatewayserver.security.CsrfGuard;
import ru.zuzex.practice.gatewayserver.security.GatewayProblems;
import ru.zuzex.practice.gatewayserver.security.jwt.JwtTokenUtils;

@Component
@RequiredArgsConstructor
public class JwtValidationGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> {

    private final JwtTokenUtils jwtTokenUtils;
    private final CsrfGuard csrf;
    private final GatewayProblems problems;

    @Override
    public GatewayFilter apply(Object config) {
        return (exchange, chain) -> Mono.fromCallable(() -> {
                    var cookies = exchange.getRequest().getCookies().get(DownstreamRequest.ACCESS_COOKIE);
                    if (cookies == null || cookies.size() != 1 || cookies.getFirst().getValue().isBlank()) {
                        throw new JwtException("Missing access cookie");
                    }
                    return jwtTokenUtils.extractRequestOwner(cookies.getFirst().getValue());
                })
                .onErrorMap(error -> error instanceof JwtException || error instanceof IllegalArgumentException,
                        error -> new ResponseStatusException(HttpStatus.UNAUTHORIZED))
                .flatMap(owner -> csrf.validate(exchange).thenReturn(owner))
                .onErrorResume(ResponseStatusException.class,
                        error -> problems.write(exchange, error.getStatusCode()).then(Mono.empty()))
                .flatMap(owner -> {
                    var downstream = DownstreamRequest.sanitized(exchange.getRequest())
                            .headers(headers -> {
                                headers.remove("X-CSRF-Token");
                                headers.set("X-User-Id", owner.userId().toString());
                                headers.set("X-Auth-Session-Id", owner.sessionId().toString());
                            })
                            .build();
                    return chain.filter(exchange.mutate().request(downstream).build());
                });
    }
}
