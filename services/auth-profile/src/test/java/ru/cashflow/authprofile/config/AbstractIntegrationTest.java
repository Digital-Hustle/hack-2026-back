package ru.cashflow.authprofile.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static ru.cashflow.authprofile.jooq.Tables.CODE_CHALLENGES;
import static ru.cashflow.authprofile.jooq.Tables.RATE_LIMITS;
import static ru.cashflow.authprofile.jooq.Tables.USERS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import ru.cashflow.authprofile.AuthProfileApplication;
import ru.cashflow.authprofile.features.security.gateway.CodeDeliveryGateway;
import ru.cashflow.authprofile.features.security.query.service.SessionQueryService;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

@SpringBootTest(classes = AuthProfileApplication.class)
@AutoConfigureMockMvc
@Import(AbstractIntegrationTest.TimeConfig.class)
public abstract class AbstractIntegrationTest {
    protected static final String BASE = "/api/v1/auth-profile";
    protected static final String ORIGIN = "https://app.example.test";
    protected static final KeyPair KEYS = keys();
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected DSLContext dsl;
    @Autowired protected DataSource dataSource;
    @Autowired protected SessionQueryService sessionQueries;
    @Autowired protected MutableClock clock;
    @MockitoBean protected CodeDeliveryGateway delivery;
    protected final Map<String, String> delivered = new ConcurrentHashMap<>();
    protected Cookie csrfCookie;
    protected String csrf;

    @DynamicPropertySource
    protected static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", PostgresTestSupport::url);
        p.add("spring.datasource.username", () -> "postgres");
        p.add("spring.datasource.password", () -> "postgres");
        p.add("auth.allowed-origins", () -> ORIGIN);
        p.add("auth.otp-hmac-secret", () -> Base64.getEncoder().encodeToString(new byte[32]));
        p.add(
                "auth.jwt.private-key",
                () -> Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
        p.add(
                "auth.jwt.public-key",
                () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        p.add("auth.mail-from", () -> "auth@example.test");
    }

    @TestConfiguration
    protected static class TimeConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    protected static class MutableClock extends Clock {
        protected final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        public void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }
    }

    protected static KeyPair keys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    protected void resetState() throws Exception {
        dsl.truncate(USERS).cascade().execute();
        dsl.truncate(CODE_CHALLENGES).execute();
        dsl.truncate(RATE_LIMITS).execute();
        clock.now.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        delivered.clear();
        doAnswer(
                        invocation -> {
                            delivered.put(invocation.getArgument(0), invocation.getArgument(1));
                            return null;
                        })
                .when(delivery)
                .send(anyString(), anyString());
        var bootstrap =
                mvc.perform(get(BASE + "/auth/csrf").header("Origin", ORIGIN))
                        .andExpect(status().isOk())
                        .andReturn();
        csrfCookie = bootstrap.getResponse().getCookie("__Host-cashflow-csrf");
        csrf = body(bootstrap).path("token").asText();
    }

    protected List<Integer> race(Callable<Integer> operation) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Callable<Integer> concurrent =
                    () -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return operation.call();
                    };
            var a = pool.submit(concurrent);
            var b = pool.submit(concurrent);
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    protected MockHttpServletRequestBuilder mutation(MockHttpServletRequestBuilder request) {
        return request.header("Origin", ORIGIN).header("X-CSRF-Token", csrf).cookie(csrfCookie);
    }

    protected MockHttpServletRequestBuilder context(
            MockHttpServletRequestBuilder request, MvcResult login) throws Exception {
        return request.header("X-User-Id", body(login).path("user").path("id").asText())
                .header("X-Auth-Session-Id", body(login).path("sessionId").asText());
    }

    protected JsonNode challenge(String email) throws Exception {
        return body(
                mvc.perform(
                                mutation(post(BASE + "/auth/code"))
                                        .contentType("application/json")
                                        .content(json.writeValueAsString(Map.of("email", email))))
                        .andExpect(status().isAccepted())
                        .andReturn());
    }

    protected ResultActions verify(JsonNode challenge, String code) throws Exception {
        return mvc.perform(
                mutation(post(BASE + "/auth/verify"))
                        .contentType("application/json")
                        .content(
                                json.writeValueAsString(
                                        Map.of(
                                                "challengeId",
                                                challenge.path("challengeId").asText(),
                                                "code",
                                                code))));
    }

    protected MvcResult login(String email) throws Exception {
        JsonNode challenge = challenge(email);
        return verify(challenge, delivered.get(email.toLowerCase(Locale.ROOT)))
                .andExpect(status().isOk())
                .andReturn();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
}
