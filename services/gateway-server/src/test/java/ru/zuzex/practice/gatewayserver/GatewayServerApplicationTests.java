package ru.zuzex.practice.gatewayserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayServerApplicationTests {

    private static final String ACCESS = "__Host-cashflow-access";
    private static final String CSRF = "__Host-cashflow-csrf";
    private static final String REFRESH = "__Secure-cashflow-refresh";
    private static final String ORIGIN = "https://cashflow.test";
    private static final String AUTH = "/api/v1/auth-profile/auth";
    private static final String PROFILE = "/api/v1/auth-profile/profile/me";
    private static final String UPLOAD = "/api/v1/preprocessor/companies/11111111-1111-4111-8111-111111111111/imports";
    private static final String PREPARE = "/api/v1/preprocessor/statements/prepare";
    private static final UUID USER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final KeyPair KEY = keyPair();
    private static final List<Received> AUTH_REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<Received> PREPROCESSOR_REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<Received> ORCHESTRATOR_REQUESTS = new CopyOnWriteArrayList<>();
    private static final AtomicReference<Reply> ORCHESTRATOR_REPLY = new AtomicReference<>(
            new Reply(200, "{\"items\":[]}", MediaType.APPLICATION_JSON_VALUE));
    private static final HttpServer AUTH_SERVER = server(AUTH_REQUESTS, true);
    private static final HttpServer PREPROCESSOR_SERVER = server(PREPROCESSOR_REQUESTS, false);
    private static final HttpServer ORCHESTRATOR_SERVER = server(ORCHESTRATOR_REQUESTS, false);

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("GATEWAY_JWT_PUBLIC_KEY", () -> Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded()));
        registry.add("GATEWAY_ALLOWED_ORIGINS", () -> ORIGIN);
        registry.add("AUTH_PROFILE_URL", () -> "http://127.0.0.1:" + AUTH_SERVER.getAddress().getPort());
        registry.add("PREPROCESSOR_URL", () -> "http://127.0.0.1:" + PREPROCESSOR_SERVER.getAddress().getPort());
        registry.add("ORCHESTRATOR_URL", () -> "http://127.0.0.1:" + ORCHESTRATOR_SERVER.getAddress().getPort());
    }

    @BeforeEach
    void clearRequests() {
        webTestClient = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        AUTH_REQUESTS.clear();
        PREPROCESSOR_REQUESTS.clear();
        ORCHESTRATOR_REQUESTS.clear();
        ORCHESTRATOR_REPLY.set(new Reply(200, "{\"items\":[]}", MediaType.APPLICATION_JSON_VALUE));
    }

    @AfterAll
    static void stopServers() {
        AUTH_SERVER.stop(0);
        PREPROCESSOR_SERVER.stop(0);
        ORCHESTRATOR_SERVER.stop(0);
    }

    @Test
    void startsAndServesHealthWithoutConfigServer() {
        webTestClient.get().uri("/actuator/health").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UP");
        assertThat(AUTH_REQUESTS).isEmpty();
    }

    @Test
    void profileAcceptsAuthProfileRs256CookieAndReplacesSpoofedContext() {
        webTestClient.get().uri(PROFILE + "?view=full")
                .cookie(ACCESS, token(b -> b))
                .cookie(REFRESH, "refresh-secret")
                .cookie(CSRF, "csrf-valid")
                .cookie("unrelated", "secret")
                .header(HttpHeaders.AUTHORIZATION, "Bearer should-not-leak")
                .header("x-user-id", "spoofed", "duplicate")
                .header("X-User-Roles", "ADMIN")
                .header("X-User-Custom", "spoofed")
                .header("x-auth-session-id", "spoofed")
                .header("X-Auth-Other", "spoofed")
                .exchange().expectStatus().isOk();

        assertThat(AUTH_REQUESTS).hasSize(1);
        var received = AUTH_REQUESTS.getFirst();
        assertThat(received.path()).isEqualTo(PROFILE + "?view=full");
        assertThat(received.headers().get("X-User-Id")).containsExactly(USER.toString());
        assertThat(received.headers().get("X-Auth-Session-Id")).containsExactly(SESSION.toString());
        assertThat(received.headers()).doesNotContainKeys(
                HttpHeaders.AUTHORIZATION, HttpHeaders.COOKIE, "X-User-Roles", "X-User-Custom", "X-Auth-Other");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "expired", "future", "issuer", "audience", "kid",
            "subject", "session", "missing-session", "no-expiry", "wrong-key", "hmac", "rs512", "unsigned"})
    void rejectsInvalidAccessBeforeCallingServices(String invalid) {
        String access = switch (invalid) {
            case "malformed" -> "broken";
            case "expired" -> token(b -> b.expiration(Date.from(Instant.now().minusSeconds(60))));
            case "future" -> token(b -> b.notBefore(Date.from(Instant.now().plusSeconds(600))));
            case "issuer" -> token(b -> b.issuer("other"));
            case "audience" -> token(b -> b.audience().clear().add("other").and());
            case "kid" -> token(b -> b.header().keyId("other").and());
            case "subject" -> token(b -> b.subject("not-a-uuid"));
            case "session" -> token(b -> b.claim("sid", "1-1-1-1-1"));
            case "missing-session" -> token(b -> b.claim("sid", null));
            case "no-expiry" -> token(b -> b.expiration(null));
            case "wrong-key" -> token(b -> b.signWith(keyPair().getPrivate(), Jwts.SIG.RS256));
            case "hmac" -> token(b -> b.signWith(Keys.hmacShaKeyFor(new byte[32]), Jwts.SIG.HS256));
            case "rs512" -> token(b -> b.signWith(KEY.getPrivate(), Jwts.SIG.RS512));
            case "unsigned" -> Jwts.builder().subject(USER.toString()).compact();
            default -> null;
        };
        var request = webTestClient.get().uri(PROFILE);
        if (access != null) {
            request.cookie(ACCESS, access);
        }
        request.exchange().expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(401);
        assertThat(AUTH_REQUESTS).isEmpty();
        assertThat(PREPROCESSOR_REQUESTS).isEmpty();
    }

    @Test
    void rejectsDuplicateAccessCookies() {
        webTestClient.get().uri(PROFILE).cookie(ACCESS, token(b -> b)).cookie(ACCESS, token(b -> b))
                .exchange().expectStatus().isUnauthorized();
        assertThat(AUTH_REQUESTS).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"csrf", "code", "verify", "refresh", "logout"})
    void lifecyclePreservesPathAndOnlyRequiredCookiesWithoutAccessToken(String endpoint) {
        var request = endpoint.equals("csrf") ? webTestClient.get().uri(AUTH + "/" + endpoint)
                : webTestClient.post().uri(AUTH + "/" + endpoint);
        request.header(HttpHeaders.ORIGIN, ORIGIN)
                .header("X-CSRF-Token", "csrf-token")
                .header("X-User-Id", "spoofed")
                .header("X-Auth-Session-Id", "spoofed")
                .header(HttpHeaders.AUTHORIZATION, "Bearer secret")
                .cookie(ACCESS, "expired-access")
                .cookie(CSRF, "csrf-valid")
                .cookie(REFRESH, "refresh-secret")
                .cookie("unrelated", "secret")
                .exchange().expectStatus().isOk()
                .expectHeader().values(HttpHeaders.SET_COOKIE, values -> assertThat(values).hasSize(2))
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals("X-Trace-Id", "auth-trace");

        assertThat(AUTH_REQUESTS).hasSize(1);
        var received = AUTH_REQUESTS.getFirst();
        assertThat(received.path()).isEqualTo(AUTH + "/" + endpoint);
        assertThat(received.headers()).doesNotContainKeys(
                HttpHeaders.AUTHORIZATION, "X-User-Id", "X-Auth-Session-Id");
        assertThat(received.headers().getFirst("X-CSRF-Token")).isEqualTo("csrf-token");
        String cookies = received.headers().getFirst(HttpHeaders.COOKIE);
        assertThat(cookies).contains(CSRF + "=csrf-valid").doesNotContain(ACCESS, "unrelated");
        if (endpoint.equals("refresh") || endpoint.equals("logout")) {
            assertThat(cookies).contains(REFRESH + "=refresh-secret");
        } else {
            assertThat(cookies).doesNotContain(REFRESH);
        }
    }

    @Test
    void protectedMutationChecksAuthOwnedCsrfBindingThenStripsCookies() {
        webTestClient.put().uri(PROFILE).cookie(ACCESS, token(b -> b)).cookie(CSRF, "csrf-valid")
                .header(HttpHeaders.ORIGIN, ORIGIN).header("X-CSRF-Token", "csrf-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"displayName\":\"User\"}")
                .exchange().expectStatus().isOk();

        assertThat(AUTH_REQUESTS).hasSize(2);
        var csrfCheck = AUTH_REQUESTS.getFirst();
        assertThat(csrfCheck.path()).isEqualTo(AUTH + "/csrf");
        assertThat(csrfCheck.headers().getFirst(HttpHeaders.COOKIE)).isEqualTo(CSRF + "=csrf-valid");
        assertThat(csrfCheck.headers()).doesNotContainKeys(HttpHeaders.AUTHORIZATION, "X-User-Id");
        var profile = AUTH_REQUESTS.getLast();
        assertThat(profile.method()).isEqualTo("PUT");
        assertThat(profile.headers()).doesNotContainKeys(HttpHeaders.COOKIE, "X-CSRF-Token");
        assertThat(profile.headers().getFirst("X-Auth-Session-Id")).isEqualTo(SESSION.toString());
        assertThat(new String(profile.body(), StandardCharsets.UTF_8)).contains("displayName");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-header", "wrong-header", "missing-cookie", "invalid-cookie", "duplicate-header"})
    void rejectsProtectedMutationWithoutValidCsrf(String invalid) {
        var request = webTestClient.delete().uri(AUTH + "/sessions")
                .cookie(ACCESS, token(b -> b)).header(HttpHeaders.ORIGIN, ORIGIN);
        if (!invalid.equals("missing-cookie")) {
            request.cookie(CSRF, invalid.equals("invalid-cookie") ? "expired-csrf" : "csrf-valid");
        }
        if (!invalid.equals("missing-header")) {
            request.header("X-CSRF-Token", invalid.equals("wrong-header") ? "incorrect" : "csrf-token");
        }
        if (invalid.equals("duplicate-header")) {
            request.header("X-CSRF-Token", "csrf-token");
        }
        request.exchange().expectStatus().isForbidden();
        assertThat(AUTH_REQUESTS).allSatisfy(received -> assertThat(received.path()).isEqualTo(AUTH + "/csrf"));
    }

    @Test
    void csrfDependencyFailureReturns503WithoutForwardingMutation() {
        webTestClient.delete().uri(AUTH + "/sessions").cookie(ACCESS, token(b -> b))
                .cookie(CSRF, "auth-unavailable").header(HttpHeaders.ORIGIN, ORIGIN)
                .header("X-CSRF-Token", "csrf-token").exchange().expectStatus().isEqualTo(503);
        assertThat(AUTH_REQUESTS).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {PREPARE, UPLOAD})
    void streamsMultipartToPreprocessorWithExactPathAndNoTokens(String path) {
        String body = "--csv-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"statement.csv\""
                + "\r\nContent-Type: text/csv\r\n\r\ncompany_id;operation_id\nC001;OP1\n"
                + "x".repeat(300_000) + "\r\n--csv-boundary--\r\n";
        webTestClient.post().uri(path).cookie(ACCESS, token(b -> b)).cookie(CSRF, "csrf-valid")
                .cookie(REFRESH, "secret").header(HttpHeaders.ORIGIN, ORIGIN)
                .header("X-CSRF-Token", "csrf-token").header("X-User-Id", "spoofed")
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=csv-boundary"))
                .bodyValue(body).exchange().expectStatus().isOk();
        assertThat(PREPROCESSOR_REQUESTS).hasSize(1);
        var received = PREPROCESSOR_REQUESTS.getFirst();
        assertThat(received.path()).isEqualTo(path.equals(PREPARE) ? "/internal/v1/statements/prepare" : UPLOAD);
        assertThat(received.method()).isEqualTo("POST");
        assertThat(received.body()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        assertThat(received.headers().getFirst("X-User-Id")).isEqualTo(USER.toString());
        assertThat(received.headers()).doesNotContainKeys(HttpHeaders.COOKIE, HttpHeaders.AUTHORIZATION, "X-CSRF-Token");
    }

    @ParameterizedTest
    @ValueSource(strings = {PREPARE, UPLOAD})
    void unauthenticatedCsvUploadNeverReachesPreprocessor(String path) {
        webTestClient.post().uri(path).exchange().expectStatus().isUnauthorized();
        assertThat(PREPROCESSOR_REQUESTS).isEmpty();
    }

    @Test
    void corsPreflightDoesNotRequireLoginAndUsesConfiguredOrigin() {
        webTestClient.options().uri(PREPARE).header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type,X-CSRF-Token")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        assertThat(AUTH_REQUESTS).isEmpty();
        assertThat(PREPROCESSOR_REQUESTS).isEmpty();
    }

    @Test
    void rejectsUntrustedCorsOrigin() {
        webTestClient.put().uri(PROFILE).cookie(ACCESS, token(b -> b))
                .header(HttpHeaders.ORIGIN, "https://evil.test").exchange().expectStatus().isForbidden();
        assertThat(AUTH_REQUESTS).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("orchestratorRoutes")
    void forwardsImplementedOrchestratorRoutesWithTrustedIdentityAndOriginalRequest(String method, String path) {
        var request = webTestClient.method(HttpMethod.valueOf(method)).uri(path + "?limit=10&offset=0")
                .cookie(ACCESS, token(b -> b)).cookie(REFRESH, "must-not-leak")
                .header("x-user-id", "spoofed", "duplicate").header("X-User-Roles", "OWNER")
                .header("X-Auth-Session-Id", "spoofed").header(HttpHeaders.AUTHORIZATION, "Bearer must-not-leak")
                .header("If-Match", "\"7\"").header("Idempotency-Key", "gateway-audit-01");
        boolean mutation = !method.equals("GET");
        String body = "{\"scope\":\"HISTORY_ONLY\"}";
        if (mutation) {
            request.cookie(CSRF, "csrf-valid").header(HttpHeaders.ORIGIN, ORIGIN)
                    .header("X-CSRF-Token", "csrf-token").contentType(MediaType.APPLICATION_JSON);
            request.bodyValue(body).exchange().expectStatus().isOk();
        } else {
            request.exchange().expectStatus().isOk();
        }

        assertThat(ORCHESTRATOR_REQUESTS).hasSize(1);
        var received = ORCHESTRATOR_REQUESTS.getFirst();
        assertThat(received.method()).isEqualTo(method);
        assertThat(received.path()).isEqualTo(path + "?limit=10&offset=0");
        assertThat(received.headers().get("X-User-Id")).containsExactly(USER.toString());
        assertThat(received.headers().get("X-Auth-Session-Id")).containsExactly(SESSION.toString());
        assertThat(received.headers().getFirst("If-Match")).isEqualTo("\"7\"");
        assertThat(received.headers().getFirst("Idempotency-Key")).isEqualTo("gateway-audit-01");
        assertThat(received.headers()).doesNotContainKeys(HttpHeaders.COOKIE, HttpHeaders.AUTHORIZATION,
                "X-User-Roles", "X-CSRF-Token");
        if (mutation) {
            assertThat(received.body()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        }
        assertThat(AUTH_REQUESTS).hasSize(mutation ? 1 : 0);
        assertThat(PREPROCESSOR_REQUESTS).isEmpty();
    }

    @Test
    void orchestratorRoutesRequireLoginAndMutationsRequireCsrfBeforeForwarding() {
        String path = "/api/v1/orchestrator/companies";
        webTestClient.get().uri(path).exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri(path).cookie(ACCESS, token(b -> b))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isForbidden();
        assertThat(ORCHESTRATOR_REQUESTS).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"data-quality", "completeness-ranges"})
    void qualityReadsNeedLoginAndDoNotExposeUnknownSubresources(String resource) {
        String base = "/api/v1/orchestrator/companies/c/accounts/a/" + resource;
        webTestClient.get().uri(base).exchange().expectStatus().isUnauthorized();
        assertThat(ORCHESTRATOR_REQUESTS).isEmpty();
        webTestClient.get().uri(base + "/internal").cookie(ACCESS, token(b -> b))
                .exchange().expectStatus().isNotFound();
        assertThat(ORCHESTRATOR_REQUESTS).isEmpty();
    }

    @Test
    void alertReadsAndDetailsRequireLoginAndAcknowledgementRequiresCsrf() {
        String account = "/api/v1/orchestrator/companies/c/accounts/a";
        webTestClient.get().uri(account + "/alerts").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri(account + "/forecasts/f/gaps/g").exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri(account + "/alerts/i/acknowledge").cookie(ACCESS, token(b -> b))
                .exchange().expectStatus().isForbidden();
        assertThat(ORCHESTRATOR_REQUESTS).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"alerts/i/acknowledge", "alerts/i/acknowledge/extra", "forecasts/f/gaps/g/extra"})
    void alertRoutesDoNotExposeUnsupportedReads(String path) {
        webTestClient.get().uri("/api/v1/orchestrator/companies/c/accounts/a/" + path)
                .cookie(ACCESS, token(b -> b)).exchange().expectStatus().isNotFound();
        assertThat(ORCHESTRATOR_REQUESTS).isEmpty();
    }

    @Test
    void corsAllowsOrchestratorMemberPatchWithVersionAndIdempotencyHeaders() {
        webTestClient.options().uri("/api/v1/orchestrator/companies/c/members/u")
                .header(HttpHeaders.ORIGIN, ORIGIN).header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type,X-CSRF-Token,If-Match,Idempotency-Key")
                .exchange().expectStatus().isOk()
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, value -> assertThat(value).contains("PATCH"));
        assertThat(ORCHESTRATOR_REQUESTS).isEmpty();
        assertThat(AUTH_REQUESTS).isEmpty();
    }

    @Test
    void preservesOrchestratorProblemAndResponseMetadata() {
        ORCHESTRATOR_REPLY.set(new Reply(503, "{\"code\":\"DEPENDENCY_UNAVAILABLE\",\"status\":503}",
                MediaType.APPLICATION_PROBLEM_JSON_VALUE));
        webTestClient.get().uri("/api/v1/orchestrator/companies").cookie(ACCESS, token(b -> b))
                .exchange().expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals("Retry-After", "1").expectHeader().valueEquals("ETag", "\"7\"")
                .expectBody().jsonPath("$.code").isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(ORCHESTRATOR_REQUESTS).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/v1/forecast/calculate", "/internal/v1/orchestrator/access-check",
            "/api/v1/forecast/calculate", "/api/v1/orchestrator/actuator/health", "/api/v1/orchestrator/health",
            "/api/v1/orchestrator/companies/c/unknown"})
    void newRoutesDoNotExposeInternalForecastOrUnknownOrchestratorResources(String path) {
        webTestClient.get().uri(path).cookie(ACCESS, token(b -> b)).exchange().expectStatus().isNotFound();
        assertThat(ORCHESTRATOR_REQUESTS).isEmpty();
        assertThat(AUTH_REQUESTS).isEmpty();
    }

    private static Stream<Arguments> orchestratorRoutes() {
        String companies = "/api/v1/orchestrator/companies";
        String company = companies + "/11111111-1111-4111-8111-111111111111";
        String account = company + "/accounts/22222222-2222-4222-8222-222222222222";
        String employees = company + "/employees";
        Stream<Arguments> employeeRoutes = Stream.of(
                Arguments.of("GET", employees), Arguments.of("POST", employees),
                Arguments.of("GET", employees + "/e"), Arguments.of("PUT", employees + "/e"),
                Arguments.of("PUT", employees + "/e/salary"), Arguments.of("PUT", employees + "/e/recurring-payment"),
                Arguments.of("DELETE", employees + "/e/recurring-payment"), Arguments.of("POST", employees + "/e/archive"));
        Stream<Arguments> companyRoutes = Stream.of(
                Arguments.of("GET", companies), Arguments.of("POST", companies),
                Arguments.of("GET", company), Arguments.of("PUT", company),
                Arguments.of("GET", company + "/categories"), Arguments.of("GET", company + "/counterparties"),
                Arguments.of("POST", company + "/archive"), Arguments.of("POST", account + "/archive"),
                Arguments.of("GET", company + "/members"), Arguments.of("POST", company + "/members"),
                Arguments.of("PATCH", company + "/members/33333333-3333-4333-8333-333333333333"),
                Arguments.of("DELETE", company + "/members/33333333-3333-4333-8333-333333333333"),
                Arguments.of("GET", company + "/accounts"), Arguments.of("POST", company + "/accounts"),
                Arguments.of("POST", company + "/payments"),
                Arguments.of("POST", company + "/payments/33333333-3333-4333-8333-333333333333/void"),
                Arguments.of("POST", company + "/payments/33333333-3333-4333-8333-333333333333/duplicate-resolutions"),
                Arguments.of("GET", company + "/payments/33333333-3333-4333-8333-333333333333/duplicate-candidates"),
                Arguments.of("GET", company + "/plan-fact"),
                Arguments.of("GET", company + "/tax-calendar"),
                Arguments.of("GET", account), Arguments.of("PUT", account),
                Arguments.of("GET", company + "/jobs/44444444-4444-4444-8444-444444444444"),
                Arguments.of("POST", company + "/jobs/44444444-4444-4444-8444-444444444444/cancel"));
        Stream<Arguments> reads = Stream.of("payments", "payments/p/revisions", "daily-history",
                "data-quality", "completeness-ranges", "imports",
                "imports/i", "imports/i/issues", "recurring-patterns", "expected-payments", "expected-payments/p",
                "expected-payments/p/allocations", "forecast-settings", "jobs", "forecasts", "forecasts/latest",
                "forecasts/f", "forecasts/f/gaps/g", "alerts", "backtests", "backtests/b", "backtests/b/origins",
                "backtests/b/trace", "backtests/b/export", "backtests/b/report", "forecasts/f/trace",
                "forecasts/f/export", "dashboard", "payment-calendar", "integrations/bitrix24")
                .map(path -> Arguments.of("GET", account + "/" + path));
        Stream<Arguments> writes = Stream.of("completeness", "balance-anchors", "imports", "imports/i/finalize",
                "imports/i/cancel", "expected-payments", "expected-payments/p/cancel", "expected-payments/p/allocations",
                "allocations/a/reverse", "forecast-jobs", "backtest-jobs", "recurring-detection-jobs", "alerts/a/acknowledge",
                "recurring-patterns/confirm-detected", "integrations/bitrix24/sync", "integrations/onec/sync")
                .map(path -> Arguments.of("POST", account + "/" + path));
        Stream<Arguments> updates = Stream.of("payments/p/category", "recurring-patterns/r/status", "expected-payments/p",
                "forecast-settings").map(path -> Arguments.of("PUT", account + "/" + path));
        return Stream.of(companyRoutes, employeeRoutes, reads, writes, updates).flatMap(stream -> stream);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal/v1/statements/prepare", "/api/v1/preprocessor/actuator/health",
            "/api/v1/auth-profile/actuator/health", "/api/v1/auth-profile/auth/verify/extra", "/actuator/env"})
    void doesNotExposeInternalOrManagementPaths(String path) {
        webTestClient.get().uri(path).exchange().expectStatus().isNotFound();
        assertThat(AUTH_REQUESTS).isEmpty();
        assertThat(PREPROCESSOR_REQUESTS).isEmpty();
    }

    private static String token(UnaryOperator<JwtBuilder> change) {
        Instant now = Instant.now();
        var builder = Jwts.builder().header().keyId("cashflow-1").and()
                .issuer("https://auth.cashflow.internal").audience().add("cashflow-api").and()
                .subject(USER.toString()).claim("sid", SESSION.toString()).claim("roles", List.of("USER"))
                .issuedAt(Date.from(now)).notBefore(Date.from(now.minusSeconds(1)))
                .expiration(Date.from(now.plusSeconds(900))).signWith(KEY.getPrivate(), Jwts.SIG.RS256);
        return change.apply(builder).compact();
    }

    private static KeyPair keyPair() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static HttpServer server(List<Received> requests, boolean auth) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> respond(exchange, requests, auth));
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void respond(HttpExchange exchange, List<Received> requests, boolean auth) throws IOException {
        var headers = new HttpHeaders();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
        requests.add(new Received(exchange.getRequestURI().toString(), exchange.getRequestMethod(),
                headers, exchange.getRequestBody().readAllBytes()));
        if (requests == ORCHESTRATOR_REQUESTS) {
            var reply = ORCHESTRATOR_REPLY.get();
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, reply.contentType());
            exchange.getResponseHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
            exchange.getResponseHeaders().set("Retry-After", "1");
            exchange.getResponseHeaders().set("ETag", "\"7\"");
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        String cookie = headers.getFirst(HttpHeaders.COOKIE);
        boolean unavailable = cookie != null && cookie.contains("auth-unavailable");
        String csrf = cookie != null && cookie.contains(CSRF + "=csrf-valid") ? "csrf-token" : "new-token";
        byte[] body = ("{\"token\":\"" + csrf + "\",\"headerName\":\"X-CSRF-Token\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        exchange.getResponseHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
        if (auth) {
            exchange.getResponseHeaders().add(HttpHeaders.SET_COOKIE, CSRF + "=csrf-valid; Path=/; Secure; HttpOnly");
            exchange.getResponseHeaders().add(HttpHeaders.SET_COOKIE, ACCESS + "=test; Path=/; Secure; HttpOnly");
            exchange.getResponseHeaders().set("X-Trace-Id", "auth-trace");
        }
        exchange.sendResponseHeaders(unavailable ? 503 : 200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record Received(String path, String method, HttpHeaders headers, byte[] body) {
    }

    private record Reply(int status, String body, String contentType) {
    }
}
