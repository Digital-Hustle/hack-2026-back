package ru.zuzex.practice.gatewayserver.filter;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class AuthLifecycleGatewayFilterFactory extends AbstractGatewayFilterFactory<Object> {

    @Override
    public GatewayFilter apply(Object config) {
        return (exchange, chain) -> {
            var request = exchange.getRequest();
            String path = request.getPath().value();
            boolean refresh = path.endsWith("/refresh") || path.endsWith("/logout");
            String cookies = request.getCookies().values().stream()
                    .flatMap(java.util.Collection::stream)
                    .filter(cookie -> DownstreamRequest.CSRF_COOKIE.equals(cookie.getName())
                            || (refresh && DownstreamRequest.REFRESH_COOKIE.equals(cookie.getName())))
                    .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                    .collect(Collectors.joining("; "));
            var downstream = DownstreamRequest.sanitized(request);
            if (!cookies.isEmpty()) {
                downstream.header(HttpHeaders.COOKIE, cookies);
            }
            return chain.filter(exchange.mutate().request(downstream.build()).build());
        };
    }
}
