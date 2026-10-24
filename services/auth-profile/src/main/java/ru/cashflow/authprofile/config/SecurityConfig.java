package ru.cashflow.authprofile.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import ru.cashflow.authprofile.api.cookie.AuthCookieManager;
import ru.cashflow.authprofile.api.security.CsrfRequestGuard;
import ru.cashflow.authprofile.exception.ProblemWriter;
import ru.cashflow.authprofile.features.security.command.service.SessionActivityCommandService;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain security(
            HttpSecurity http,
            SessionActivityCommandService sessions,
            CsrfRequestGuard csrf,
            ProblemWriter problems,
            AuthCookieManager cookies)
            throws Exception {
        // Lifecycle CSRF is checked by AuthBoundaryFilter; protected mutations are checked at
        // Gateway,
        // which deliberately removes cookies before forwarding trusted context.
        return http.csrf(c -> c.disable())
                .formLogin(c -> c.disable())
                .httpBasic(c -> c.disable())
                .logout(c -> c.disable())
                .requestCache(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(c -> c.anyRequest().permitAll())
                .addFilterBefore(
                        new AuthBoundaryFilter(sessions, csrf, problems, cookies),
                        AnonymousAuthenticationFilter.class)
                .build();
    }
}
