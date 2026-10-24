package ru.cashflow.authprofile.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static ru.cashflow.authprofile.jooq.Tables.SESSIONS;
import static ru.cashflow.authprofile.jooq.Tables.USERS;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import ru.cashflow.authprofile.config.AbstractIntegrationTest;
import ru.cashflow.authprofile.exception.DependencyUnavailableException;

import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

class AuthProfileIntegrationTest extends AbstractIntegrationTest {
    @Test
    void firstLoginCreatesOneNormalizedUserAndSecureCookiesWithVerifiableJwt() throws Exception {
        var login = login("USER@example.com");
        JsonNode user = body(login).path("user");
        assertThat(user.path("email").asText()).isEqualTo("user@example.com");
        assertThat(user.path("displayName").isNull()).isTrue();
        assertThat(user.path("version").asLong()).isZero();
        assertThat(body(login).has("accessToken")).isFalse();
        assertThat(body(login).has("refreshToken")).isFalse();
        var access = login.getResponse().getCookie("__Host-cashflow-access");
        assertThat(access).isNotNull();
        assertThat(access.isHttpOnly()).isTrue();
        assertThat(access.getSecure()).isTrue();
        assertThat(access.getPath()).isEqualTo("/");
        assertThat(access.getDomain()).isNull();
        assertThat(login.getResponse().getHeaders("Set-Cookie"))
                .allMatch(h -> h.contains("SameSite=Lax"));
        var token =
                NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic())
                        .build()
                        .decode(access.getValue());
        assertThat(token.getSubject()).isEqualTo(user.path("id").asText());
        assertThat(token.getClaimAsString("sid")).isEqualTo(body(login).path("sessionId").asText());
        assertThat(token.getIssuer().toString()).isEqualTo("https://auth.cashflow.internal");
        assertThat(token.getAudience()).contains("cashflow-api");
        assertThat(token.getExpiresAt())
                .isEqualTo(Instant.parse(body(login).path("accessExpiresAt").asText()));
        clock.advance(Duration.ofSeconds(61));
        var second = login("user@example.com");
        assertThat(body(second).path("user").path("id")).isEqualTo(user.path("id"));
        mvc.perform(context(get(BASE + "/auth/sessions"), second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].current").value(true));
    }

    @Test
    void rejectsSpoofedMissingDuplicateAndRevokedSessionContext() throws Exception {
        mvc.perform(get(BASE + "/profile/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        var login = login("a@example.com");
        mvc.perform(
                        context(get(BASE + "/profile/me"), login)
                                .header("X-User-Id", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        get(BASE + "/profile/me")
                                .header("X-User-Id", UUID.randomUUID())
                                .header(
                                        "X-Auth-Session-Id",
                                        body(login).path("sessionId").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(context(delete(BASE + "/auth/sessions"), login))
                .andExpect(status().isNoContent());
        mvc.perform(context(get(BASE + "/profile/me"), login)).andExpect(status().isUnauthorized());
    }

    @Test
    void profileVersionIsAtomicAndProtectedAgainstMassAssignment() throws Exception {
        var login = login("a@example.com");
        mvc.perform(
                        context(put(BASE + "/profile/me"), login)
                                .contentType("application/json")
                                .content("{\"displayName\":\"Иван\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(
                        context(put(BASE + "/profile/me"), login)
                                .contentType("application/json")
                                .content("{\"displayName\":\"Пётр\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_VERSION_CONFLICT"));
        for (String payload :
                List.of(
                        "{\"displayName\":\" \",\"version\":1}",
                        "{\"displayName\":22,\"version\":1}",
                        "{\"displayName\":\"A\",\"version\":1.5}",
                        "{\"displayName\":\"A\",\"version\":1,\"email\":\"other@example.com\"}")) {
            mvc.perform(
                            context(put(BASE + "/profile/me"), login)
                                    .contentType("application/json")
                                    .content(payload))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(context(get(BASE + "/profile/me"), login))
                .andExpect(jsonPath("$.displayName").value("Иван"))
                .andExpect(jsonPath("$.email").value("a@example.com"));
    }

    @Test
    void csrfRequiresMatchingBindingAndExactOriginAndBootstrapReusesCookie() throws Exception {
        mvc.perform(get(BASE + "/auth/csrf").header("Origin", "https://evil.test"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(
                        post(BASE + "/auth/code")
                                .contentType("application/json")
                                .content("{\"email\":\"a@example.com\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        mutation(post(BASE + "/auth/code"))
                                .header("Origin", "https://evil.test")
                                .contentType("application/json")
                                .content("{\"email\":\"a@example.com\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/auth/csrf").header("Origin", ORIGIN).cookie(csrfCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(csrf));
        mvc.perform(
                        post(BASE + "/auth/code")
                                .header("Origin", ORIGIN)
                                .header("X-CSRF-Token", csrf)
                                .cookie(new Cookie("__Host-cashflow-csrf", "forged"))
                                .contentType("application/json")
                                .content("{\"email\":\"a@example.com\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void codeIsSingleUseAndWrongAttemptCountersSurviveErrors() throws Exception {
        JsonNode challenge = challenge("a@example.com");
        String code = delivered.get("a@example.com");
        String wrong = code.equals("000000") ? "000001" : "000000";
        for (int i = 0; i < 4; i++)
            verify(challenge, wrong)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("CODE_INVALID"));
        verify(challenge, wrong)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("CODE_ATTEMPTS_EXCEEDED"))
                .andExpect(header().exists("Retry-After"));
        verify(challenge, code).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofSeconds(61));
        JsonNode next = challenge("a@example.com");
        verify(next, delivered.get("a@example.com")).andExpect(status().isOk());
        verify(next, delivered.get("a@example.com")).andExpect(status().isUnauthorized());
    }

    @Test
    void resendInvalidatesOldCodeAndExpiredCodeCannotCreateUser() throws Exception {
        JsonNode old = challenge("a@example.com");
        String oldCode = delivered.get("a@example.com");
        mvc.perform(
                        mutation(post(BASE + "/auth/code"))
                                .contentType("application/json")
                                .content("{\"email\":\"a@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        clock.advance(Duration.ofSeconds(61));
        JsonNode next = challenge("a@example.com");
        verify(old, oldCode).andExpect(status().isUnauthorized());
        clock.advance(Duration.ofMinutes(6));
        verify(next, delivered.get("a@example.com"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CODE_EXPIRED"));
        assertThat(dsl.fetchCount(USERS)).isZero();
    }

    @Test
    void refreshPreservesSessionAndReuseRevokesWinningCredential() throws Exception {
        var login = login("a@example.com");
        Cookie old = login.getResponse().getCookie("__Secure-cashflow-refresh");
        var refreshed =
                mvc.perform(mutation(post(BASE + "/auth/refresh")).cookie(old))
                        .andExpect(status().isOk())
                        .andReturn();
        assertThat(body(refreshed).path("sessionId")).isEqualTo(body(login).path("sessionId"));
        var current = refreshed.getResponse().getCookie("__Secure-cashflow-refresh");
        assertThat(current.getValue()).isNotEqualTo(old.getValue());
        var replay =
                mvc.perform(mutation(post(BASE + "/auth/refresh")).cookie(old))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("REFRESH_REUSED"))
                        .andReturn();
        assertThat(replay.getResponse().getCookie("__Host-cashflow-access").getMaxAge()).isZero();
        mvc.perform(mutation(post(BASE + "/auth/refresh")).cookie(current))
                .andExpect(status().isUnauthorized());
        mvc.perform(context(get(BASE + "/profile/me"), login)).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyOwnerCanRevokeAndRevokingCurrentSessionClearsEveryCookie() throws Exception {
        var a = login("a@example.com");
        var b = login("b@example.com");
        mvc.perform(
                        context(
                                delete(
                                        BASE
                                                + "/auth/sessions/"
                                                + body(b).path("sessionId").asText()),
                                a))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
        var revoke =
                mvc.perform(
                                context(
                                        delete(
                                                BASE
                                                        + "/auth/sessions/"
                                                        + body(a).path("sessionId").asText()),
                                        a))
                        .andExpect(status().isNoContent())
                        .andReturn();
        assertThat(revoke.getResponse().getHeaders("Set-Cookie"))
                .hasSize(3)
                .allMatch(h -> h.contains("Max-Age=0"));
        mvc.perform(context(get(BASE + "/profile/me"), a)).andExpect(status().isUnauthorized());
        mvc.perform(context(get(BASE + "/profile/me"), b)).andExpect(status().isOk());
    }

    @Test
    void logoutIsIdempotentAndDoesNotRequireAccessContext() throws Exception {
        var login = login("a@example.com");
        Cookie refresh = login.getResponse().getCookie("__Secure-cashflow-refresh");
        mvc.perform(mutation(post(BASE + "/auth/logout")).cookie(refresh))
                .andExpect(status().isNoContent());
        mvc.perform(mutation(post(BASE + "/auth/logout")).cookie(refresh))
                .andExpect(status().isNoContent());
        mvc.perform(mutation(post(BASE + "/auth/refresh")).cookie(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
    }

    @Test
    void failedDeliveryIs503AndDoesNotPretendToCreateAnAccount() throws Exception {
        doThrow(
                        new DependencyUnavailableException(
                                new IllegalStateException("sensitive smtp failure")))
                .when(delivery)
                .send(anyString(), anyString());
        mvc.perform(
                        mutation(post(BASE + "/auth/code"))
                                .contentType("application/json")
                                .content("{\"email\":\"a@example.com\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "sensitive"))));
        assertThat(dsl.fetchCount(USERS)).isZero();
    }

    @Test
    void concurrentVerifyConsumesChallengeExactlyOnce() throws Exception {
        JsonNode challenge = challenge("a@example.com");
        String code = delivered.get("a@example.com");
        List<Integer> statuses =
                race(() -> verify(challenge, code).andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        assertThat(dsl.fetchCount(SESSIONS)).isEqualTo(1);
    }

    @Test
    void concurrentRefreshDetectsReplayAndRevokesWholeSession() throws Exception {
        var login = login("a@example.com");
        var refresh = login.getResponse().getCookie("__Secure-cashflow-refresh");
        List<Integer> statuses =
                race(
                        () ->
                                mvc.perform(mutation(post(BASE + "/auth/refresh")).cookie(refresh))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        mvc.perform(context(get(BASE + "/profile/me"), login)).andExpect(status().isUnauthorized());
    }

    @Test
    void simultaneousProfileUpdatesHaveOnlyOneWinner() throws Exception {
        var login = login("a@example.com");
        List<Integer> statuses =
                race(
                        () ->
                                mvc.perform(
                                                context(put(BASE + "/profile/me"), login)
                                                        .contentType("application/json")
                                                        .content(
                                                                "{\"displayName\":\"A\",\"version\":0}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }

    @Test
    void protectedRouteWithoutGatewayContextReturnsProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/auth-profile/profile/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void publicBootstrapIgnoresBadAccessCookieAndNeverCreatesAServerSession() throws Exception {
        mvc.perform(
                        get("/api/v1/auth-profile/auth/csrf")
                                .header("Origin", "https://app.example.test")
                                .cookie(new Cookie("__Host-cashflow-access", "expired")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-Token"))
                .andExpect(cookie().httpOnly("__Host-cashflow-csrf", true))
                .andExpect(cookie().secure("__Host-cashflow-csrf", true))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
    }

    @Test
    void lifecycleMutationWithoutCsrfFailsBeforeReadingBody() throws Exception {
        mvc.perform(
                        post("/api/v1/auth-profile/auth/code")
                                .header("Origin", "https://app.example.test")
                                .contentType("application/json")
                                .content("{\"email\":\"a@example.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void encodedLifecyclePathCannotBypassCsrf() throws Exception {
        mvc.perform(
                        post(java.net.URI.create("/api/v1/auth-profile/auth/%72efresh"))
                                .header("Origin", "https://app.example.test"))
                .andExpect(
                        result ->
                                org.assertj.core.api.Assertions.assertThat(
                                                result.getResponse().getStatus())
                                        .isIn(400, 403));
    }

    @Test
    void invalidRefreshClearsCredentialsEvenWhenExceptionIsHandledByMvcAdvice() throws Exception {
        var bootstrap =
                mvc.perform(
                                get("/api/v1/auth-profile/auth/csrf")
                                        .header("Origin", "https://app.example.test"))
                        .andExpect(status().isOk())
                        .andReturn();
        var csrf =
                json.readTree(bootstrap.getResponse().getContentAsByteArray())
                        .path("token")
                        .asText();
        mvc.perform(
                        post("/api/v1/auth-profile/auth/refresh")
                                .header("Origin", "https://app.example.test")
                                .header("X-CSRF-Token", csrf)
                                .cookie(bootstrap.getResponse().getCookie("__Host-cashflow-csrf")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_INVALID"))
                .andExpect(cookie().maxAge("__Host-cashflow-access", 0))
                .andExpect(cookie().maxAge("__Secure-cashflow-refresh", 0));
    }
}
