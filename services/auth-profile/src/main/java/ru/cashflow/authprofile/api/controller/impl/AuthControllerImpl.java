package ru.cashflow.authprofile.api.controller.impl;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.authprofile.api.controller.AuthController;
import ru.cashflow.authprofile.api.cookie.AuthCookieManager;
import ru.cashflow.authprofile.api.dto.request.RequestCodeRq;
import ru.cashflow.authprofile.api.dto.request.VerifyCodeRq;
import ru.cashflow.authprofile.api.dto.response.AuthSessionRs;
import ru.cashflow.authprofile.api.dto.response.CodeChallengeRs;
import ru.cashflow.authprofile.api.dto.response.CsrfTokenRs;
import ru.cashflow.authprofile.api.dto.response.SessionsRs;
import ru.cashflow.authprofile.api.mapper.AuthRequestMapper;
import ru.cashflow.authprofile.api.mapper.AuthSessionMapper;
import ru.cashflow.authprofile.api.mapper.CodeChallengeMapper;
import ru.cashflow.authprofile.api.security.CsrfRequestGuard;
import ru.cashflow.authprofile.api.security.CurrentUser;
import ru.cashflow.authprofile.features.security.application.service.AuthApplicationService;
import ru.cashflow.authprofile.features.security.application.service.CodeDeliveryApplicationService;
import ru.cashflow.authprofile.features.security.command.model.AuthenticatedSession;
import ru.cashflow.authprofile.features.security.command.service.SessionCommandService;
import ru.cashflow.authprofile.features.security.query.service.SessionQueryService;
import ru.cashflow.authprofile.util.time.ApplicationClock;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AuthControllerImpl implements AuthController {

    private final CodeDeliveryApplicationService requests;
    private final AuthApplicationService authentication;
    private final SessionCommandService commands;
    private final SessionQueryService queries;
    private final CsrfRequestGuard csrf;
    private final AuthCookieManager cookies;
    private final AuthRequestMapper requestMapper;
    private final AuthSessionMapper sessionMapper;
    private final CodeChallengeMapper challengeMapper;
    private final HttpServletRequest request;
    private final ApplicationClock clock;

    @Override
    public ResponseEntity<CsrfTokenRs> getCsrfToken() {
        var binding = csrf.bootstrap(request);

        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookies.cookie(AuthCookieManager.CSRF, binding.cookie(), binding.maxAge()))
                .body(
                        CsrfTokenRs.builder()
                                .token(binding.header())
                                .headerName("X-CSRF-Token")
                                .build());
    }

    @Override
    public SessionsRs getSessions(CurrentUser user) {
        return sessionMapper.convert(queries.getActiveSessions(user.userId(), user.sessionId()));
    }

    @Override
    public CodeChallengeRs requestCode(RequestCodeRq body) {
        return challengeMapper.convert(
                requests.request(requestMapper.convert(body, request.getRemoteAddr())));
    }

    @Override
    public ResponseEntity<AuthSessionRs> verifyCode(VerifyCodeRq body) {
        return authenticated(
                authentication.verifyCode(requestMapper.convert(body, request.getRemoteAddr())));
    }

    @Override
    public ResponseEntity<AuthSessionRs> refresh(String refreshToken) {
        return authenticated(authentication.refresh(refreshToken));
    }

    @Override
    public ResponseEntity<Void> logout(String refreshToken) {
        commands.logout(refreshToken);

        return cleared();
    }

    @Override
    public ResponseEntity<Void> revokeSession(UUID sessionId, CurrentUser user) {
        commands.revoke(sessionId, user.userId());

        return sessionId.equals(user.sessionId()) ? cleared() : ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> revokeAllSessions(CurrentUser user) {
        commands.revokeAll(user.userId());

        return cleared();
    }

    // ResponseEntity is used only where cookies require dynamic HTTP headers.
    private ResponseEntity<AuthSessionRs> authenticated(AuthenticatedSession session) {
        var headers = new HttpHeaders();
        var tokens = session.tokens();
        var now = clock.now();
        cookies.setTokens(
                headers,
                tokens.accessToken(),
                tokens.refreshToken(),
                Duration.between(now, tokens.accessExpiresAt()),
                Duration.between(now, tokens.refreshExpiresAt()));

        return ResponseEntity.ok().headers(headers).body(sessionMapper.convert(session));
    }

    private ResponseEntity<Void> cleared() {
        var headers = new HttpHeaders();
        cookies.clear(headers, true);

        return ResponseEntity.noContent().headers(headers).build();
    }
}
