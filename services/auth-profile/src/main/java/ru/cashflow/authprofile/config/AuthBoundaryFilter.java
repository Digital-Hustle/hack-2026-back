package ru.cashflow.authprofile.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import ru.cashflow.authprofile.api.ApiPaths;
import ru.cashflow.authprofile.api.cookie.AuthCookieManager;
import ru.cashflow.authprofile.api.security.CsrfRequestGuard;
import ru.cashflow.authprofile.api.security.CurrentUser;
import ru.cashflow.authprofile.exception.DependencyUnavailableException;
import ru.cashflow.authprofile.exception.DomainException;
import ru.cashflow.authprofile.exception.ProblemWriter;
import ru.cashflow.authprofile.exception.SystemException;
import ru.cashflow.authprofile.features.security.command.service.SessionActivityCommandService;
import ru.cashflow.authprofile.features.security.exceptions.AuthenticationRequiredException;
import ru.cashflow.authprofile.features.security.exceptions.InvalidRefreshException;
import ru.cashflow.authprofile.features.security.exceptions.RefreshReusedException;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Trusts gateway context only. This service's ingress must be isolated from untrusted callers. */
@RequiredArgsConstructor
public class AuthBoundaryFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC =
            Set.of("/csrf", "/code", "/verify", "/refresh", "/logout");
    private final SessionActivityCommandService sessions;
    private final CsrfRequestGuard csrf;
    private final ProblemWriter problems;
    private final AuthCookieManager cookies;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String trace = UUID.randomUUID().toString().replace("-", "");
        request.setAttribute("traceId", trace);
        response.setHeader("X-Trace-Id", trace);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        try {
            // Match the decoded, context-relative path used by MVC, including encoded route
            // characters.
            String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
            boolean lifecycle = PUBLIC.stream().anyMatch(p -> path.equals(ApiPaths.AUTH + p));
            if (lifecycle) {
                if (!Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())) {
                    csrf.validate(request);
                }
            } else if (path.startsWith(ApiPaths.PROFILE + "/")
                    || path.equals(ApiPaths.AUTH + "/sessions")
                    || path.startsWith(ApiPaths.AUTH + "/sessions/")) {
                var user =
                        CurrentUser.builder()
                                .userId(uuidHeader(request, "X-User-Id"))
                                .sessionId(uuidHeader(request, "X-Auth-Session-Id"))
                                .build();
                sessions.recordActivity(user.userId(), user.sessionId());
                var authentication =
                        UsernamePasswordAuthenticationToken.authenticated(
                                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }

            chain.doFilter(request, response);
        } catch (DomainException | SystemException exception) {
            if (exception instanceof InvalidRefreshException
                    || exception instanceof RefreshReusedException) {
                var headers = new HttpHeaders();
                cookies.clear(headers, false);

                headers.getOrEmpty(HttpHeaders.SET_COOKIE)
                        .forEach(h -> response.addHeader(HttpHeaders.SET_COOKIE, h));
            }
            problems.write(request, response, exception);
        } catch (DataAccessException | org.jooq.exception.DataAccessException exception) {
            problems.write(request, response, new DependencyUnavailableException(exception));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UUID uuidHeader(HttpServletRequest request, String name) {
        List<String> headers = Collections.list(request.getHeaders(name));
        if (headers.size() != 1) {
            throw new AuthenticationRequiredException();
        }
        String value = headers.getFirst();
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException();
            }

            return id;
        } catch (IllegalArgumentException exception) {
            throw new AuthenticationRequiredException();
        }
    }
}
