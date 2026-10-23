package ru.cashflow.authprofile.units.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import ru.cashflow.authprofile.api.controller.AuthController;
import ru.cashflow.authprofile.api.controller.ProfileController;
import ru.cashflow.authprofile.api.dto.request.RequestCodeRq;
import ru.cashflow.authprofile.api.dto.request.UpdateProfileRq;
import ru.cashflow.authprofile.api.dto.request.VerifyCodeRq;
import ru.cashflow.authprofile.api.dto.response.AuthSessionRs;
import ru.cashflow.authprofile.api.dto.response.CodeChallengeRs;
import ru.cashflow.authprofile.api.dto.response.CsrfTokenRs;
import ru.cashflow.authprofile.api.dto.response.ProfileRs;
import ru.cashflow.authprofile.api.dto.response.SessionRs;
import ru.cashflow.authprofile.api.dto.response.SessionsRs;
import ru.cashflow.authprofile.api.json.AuthProfileJson;
import ru.cashflow.authprofile.api.security.CurrentUser;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Only the HTTP contract is exercised; fixtures do not implement authentication or persistence. */
class HttpContractTest {
    private static final String BASE = "/api/v1/auth-profile";
    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-10-24T09:00:00Z");
    private static final ProfileRs PROFILE =
            ProfileRs.builder()
                    .id(ID)
                    .email("user@example.com")
                    .displayName(null)
                    .version(0)
                    .createdAt(NOW)
                    .updatedAt(NOW)
                    .build();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var mapper = new ObjectMapper();
        AuthProfileJson.configure(mapper);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc =
                MockMvcBuilders.standaloneSetup(new AuthFixture(), new ProfileFixture())
                        .setValidator(validator)
                        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                        .setCustomArgumentResolvers(new PrincipalFixture())
                        .build();
    }

    @Test
    void exposesCsrfBootstrapAtTheGatewayPath() throws Exception {
        mvc.perform(get(BASE + "/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-Token"));
    }

    @Test
    void acceptsAnEmailAndReturnsChallengeMetadataWithoutTheCode() throws Exception {
        mvc.perform(
                        post(BASE + "/auth/code")
                                .contentType("application/json")
                                .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.challengeId").value(ID.toString()))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-24T09:05:00Z"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void acceptsACodeWithLeadingZeroesAndReturnsSessionMetadata() throws Exception {
        mvc.perform(
                        post(BASE + "/auth/verify")
                                .contentType("application/json")
                                .content("{\"challengeId\":\"" + ID + "\",\"code\":\"012345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(ID.toString()))
                .andExpect(jsonPath("$.user.email").value("user@example.com"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void refreshUsesAnOptionalCookieRatherThanAJsonToken() throws Exception {
        mvc.perform(
                        post(BASE + "/auth/refresh")
                                .cookie(
                                        new jakarta.servlet.http.Cookie(
                                                "__Secure-cashflow-refresh", "opaque-refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(ID.toString()));
    }

    @Test
    void logoutCanBeReachedWithoutAnAccessOrRefreshToken() throws Exception {
        mvc.perform(post(BASE + "/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void exposesSessionsInAnEnvelopeAndAllowsRevocation() throws Exception {
        mvc.perform(get(BASE + "/auth/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].current").value(true))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(delete(BASE + "/auth/sessions/" + ID)).andExpect(status().isNoContent());
        mvc.perform(delete(BASE + "/auth/sessions")).andExpect(status().isNoContent());
    }

    @Test
    void readsAndReplacesOnlyTheCurrentUsersEditableProfile() throws Exception {
        mvc.perform(get(BASE + "/profile/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.version").value(0));
        mvc.perform(
                        put(BASE + "/profile/me")
                                .contentType("application/json")
                                .content("{\"displayName\":\"Иван\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Иван"))
                .andExpect(jsonPath("$.version").value(1));
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("/auth/code", "{}"),
                Arguments.of("/auth/code", "{\"email\":\" \"}"),
                Arguments.of("/auth/code", "{\"email\":\"not-an-email\"}"),
                Arguments.of("/auth/verify", "{\"code\":\"012345\"}"),
                Arguments.of("/auth/verify", "{\"challengeId\":\"" + ID + "\",\"code\":\"12345\"}"),
                Arguments.of("/auth/verify", "{\"challengeId\":\"" + ID + "\",\"code\":123456}"),
                Arguments.of(
                        "/auth/verify", "{\"challengeId\":\"" + ID + "\",\"code\":\"abcdef\"}"));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsMalformedLoginRequests(String path, String json) throws Exception {
        mvc.perform(post(BASE + path).contentType("application/json").content(json))
                .andExpect(status().isBadRequest());
    }

    static Stream<String> invalidProfiles() {
        return Stream.of(
                "{\"displayName\":\"Иван\"}",
                "{\"displayName\":\" \",\"version\":0}",
                "{\"displayName\":\"Иван\",\"version\":-1}",
                "{\"displayName\":\"Иван\",\"version\":0.5}",
                "{\"displayName\":\"Иван\",\"version\":0,\"userId\":\"" + ID + "\"}");
    }

    @ParameterizedTest
    @MethodSource("invalidProfiles")
    void rejectsInvalidOrOverpostedProfileUpdates(String json) throws Exception {
        mvc.perform(put(BASE + "/profile/me").contentType("application/json").content(json))
                .andExpect(status().isBadRequest());
    }

    @RestController
    static class AuthFixture implements AuthController {
        public ResponseEntity<CsrfTokenRs> getCsrfToken() {
            return ResponseEntity.ok(
                    CsrfTokenRs.builder().token("csrf-fixture").headerName("X-CSRF-Token").build());
        }

        public CodeChallengeRs requestCode(RequestCodeRq request) {
            return CodeChallengeRs.builder()
                    .challengeId(ID)
                    .expiresAt(NOW.plusSeconds(300))
                    .resendAvailableAt(NOW.plusSeconds(60))
                    .codeLength(6)
                    .build();
        }

        public ResponseEntity<AuthSessionRs> verifyCode(VerifyCodeRq request) {
            return session();
        }

        public ResponseEntity<AuthSessionRs> refresh(String refreshToken) {
            if (!"opaque-refresh".equals(refreshToken)) return ResponseEntity.status(401).build();
            return session();
        }

        public ResponseEntity<Void> logout(String refreshToken) {
            return ResponseEntity.noContent().build();
        }

        public SessionsRs getSessions(CurrentUser user) {
            return SessionsRs.builder()
                    .items(
                            List.of(
                                    SessionRs.builder()
                                            .id(ID)
                                            .createdAt(NOW)
                                            .lastSeenAt(NOW)
                                            .expiresAt(NOW.plusSeconds(3600))
                                            .current(true)
                                            .build()))
                    .total(1)
                    .build();
        }

        public ResponseEntity<Void> revokeSession(UUID sessionId, CurrentUser user) {
            return ResponseEntity.noContent().build();
        }

        public ResponseEntity<Void> revokeAllSessions(CurrentUser user) {
            return ResponseEntity.noContent().build();
        }

        private ResponseEntity<AuthSessionRs> session() {
            return ResponseEntity.ok(
                    AuthSessionRs.builder()
                            .sessionId(ID)
                            .accessExpiresAt(NOW.plusSeconds(900))
                            .refreshExpiresAt(NOW.plusSeconds(3600))
                            .user(PROFILE)
                            .build());
        }
    }

    @RestController
    static class ProfileFixture implements ProfileController {
        public ProfileRs getMe(CurrentUser user) {
            return PROFILE;
        }

        public ProfileRs updateMe(UpdateProfileRq request, CurrentUser user) {
            return ProfileRs.builder()
                    .id(ID)
                    .email(PROFILE.email())
                    .displayName(request.displayName())
                    .version(request.version() + 1)
                    .createdAt(NOW)
                    .updatedAt(NOW)
                    .build();
        }
    }

    static class PrincipalFixture implements HandlerMethodArgumentResolver {
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == CurrentUser.class;
        }

        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer container,
                NativeWebRequest request,
                WebDataBinderFactory binder) {
            return CurrentUser.builder().userId(ID).sessionId(ID).build();
        }
    }
}
