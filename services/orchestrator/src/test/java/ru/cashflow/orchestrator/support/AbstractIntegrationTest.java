package ru.cashflow.orchestrator.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    protected final HttpClient http = HttpClient.newHttpClient();
    protected final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected Clock clock;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresTestSupport::url);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("select tablename from pg_tables where schemaname = 'public' and tablename not like 'databasechangelog%' "
                        + "and tablename <> 'tax_calendar'")
                .query(String.class).list()
                .forEach(table -> jdbc.sql("truncate table " + table + " cascade").update());
    }

    /** Keep date-based test fixtures aligned with the company's calendar rather than the runner's time zone. */
    protected LocalDate companyToday(UUID companyId) {
        String timezone = jdbc.sql("select timezone from companies where id=:company")
                .param("company", companyId).query(String.class).single();
        return LocalDate.now(clock.withZone(ZoneId.of(timezone)));
    }

    protected Response send(String method, String path, UUID user, String body, String... headers)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (user != null) {
            builder.header("X-User-Id", user.toString());
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        var response = http.send(builder.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode node = response.body() == null || response.body().isBlank() ? null : json.readTree(response.body());
        return new Response(response.statusCode(), node, response.body());
    }

    protected UUID createCompany(UUID owner, String name) throws IOException, InterruptedException {
        var response = send("POST", "/api/v1/orchestrator/companies", owner, "{\"name\":\"" + name + "\"}");
        if (response.status() != 201) {
            throw new IllegalStateException("company not created: " + response.raw());
        }
        return UUID.fromString(response.body().path("id").asText());
    }

    protected UUID createAccount(UUID user, UUID company, String externalId) throws IOException, InterruptedException {
        String external = externalId == null ? "" : ",\"externalCompanyId\":\"" + externalId + "\"";
        var response = send("POST", "/api/v1/orchestrator/companies/" + company + "/accounts", user,
                "{\"name\":\"Основной\"" + external + "}");
        if (response.status() != 201) {
            throw new IllegalStateException("account not created: " + response.raw());
        }
        return UUID.fromString(response.body().path("id").asText());
    }

    protected void addMember(UUID owner, UUID company, UUID member, String role) throws IOException, InterruptedException {
        var response = send("POST", "/api/v1/orchestrator/companies/" + company + "/members", owner,
                "{\"userId\":\"" + member + "\",\"role\":\"" + role + "\"}");
        if (response.status() != 201) {
            throw new IllegalStateException("member not added: " + response.raw());
        }
    }

    public record Response(int status, JsonNode body, String raw) {
    }
}
