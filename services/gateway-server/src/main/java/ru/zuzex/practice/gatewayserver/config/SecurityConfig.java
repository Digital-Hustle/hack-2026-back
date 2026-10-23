package ru.zuzex.practice.gatewayserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.time.Clock;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            @Value("${gateway.allowed-origins}") List<String> allowedOrigins) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-CSRF-Token", "If-Match", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("X-Trace-Id", "X-Request-Id", "Retry-After", "ETag"));
        cors.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);

        return http
                // Lifecycle validates its signed CSRF binding in Auth/Profile. JwtValidation
                // validates the same binding through Auth/Profile before stripping cookies.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .cors(config -> config.configurationSource(source))
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }
}
