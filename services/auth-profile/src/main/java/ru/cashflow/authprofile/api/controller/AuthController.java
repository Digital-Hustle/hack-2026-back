package ru.cashflow.authprofile.api.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

import ru.cashflow.authprofile.api.ApiPaths;
import ru.cashflow.authprofile.api.dto.request.RequestCodeRq;
import ru.cashflow.authprofile.api.dto.request.VerifyCodeRq;
import ru.cashflow.authprofile.api.dto.response.AuthSessionRs;
import ru.cashflow.authprofile.api.dto.response.CodeChallengeRs;
import ru.cashflow.authprofile.api.dto.response.CsrfTokenRs;
import ru.cashflow.authprofile.api.dto.response.SessionsRs;
import ru.cashflow.authprofile.api.security.CurrentUser;

import java.util.UUID;

/** Public paths are preserved by Gateway. CSRF and access policies are specified in OpenAPI. */
@RequestMapping(path = ApiPaths.AUTH, produces = MediaType.APPLICATION_JSON_VALUE)
public interface AuthController {

    @GetMapping("/csrf")
    ResponseEntity<CsrfTokenRs> getCsrfToken();

    @GetMapping("/sessions")
    SessionsRs getSessions(@AuthenticationPrincipal CurrentUser user);

    @PostMapping(path = "/code", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    CodeChallengeRs requestCode(@Valid @RequestBody RequestCodeRq request);

    @PostMapping(path = "/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AuthSessionRs> verifyCode(@Valid @RequestBody VerifyCodeRq request);

    @PostMapping("/refresh")
    ResponseEntity<AuthSessionRs> refresh(
            @CookieValue(name = ApiPaths.REFRESH_COOKIE, required = false) String refreshToken);

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = ApiPaths.REFRESH_COOKIE, required = false) String refreshToken);

    @DeleteMapping("/sessions/{sessionId}")
    ResponseEntity<Void> revokeSession(
            @PathVariable("sessionId") UUID sessionId, @AuthenticationPrincipal CurrentUser user);

    @DeleteMapping("/sessions")
    ResponseEntity<Void> revokeAllSessions(@AuthenticationPrincipal CurrentUser user);
}
