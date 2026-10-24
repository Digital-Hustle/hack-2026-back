package ru.cashflow.preprocessor.integrations;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "preprocessor.orchestrator.base-url=http://127.0.0.1:1")
class ImportUploadUnavailableTest {

    private static final String COMPANY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ACCOUNT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String USER = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String KEY = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String CSV = "company_id;operation_id;operation_date;amount;direction;counterparty_inn;"
            + "counterparty_name;payment_purpose;balance_eod\n"
            + "C001;A;2026-09-30;10.00;Поступление;;Клиент;Оплата;10.00\n";

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void returnsServiceUnavailableWhenOrchestratorIsDown() throws Exception {
        String boundary = "preprocessor-unavailable-boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"accountId\"\r\n\r\n" + ACCOUNT
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"validationMode\"\r\n\r\nATOMIC"
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"completeness\"\r\n\r\nUNKNOWN"
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"statement.csv\""
                + "\r\nContent-Type: text/csv\r\n\r\n" + CSV + "\r\n--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/preprocessor/companies/"
                        + COMPANY + "/imports"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-User-Id", USER)
                .header("Idempotency-Key", KEY)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        JsonNode problem = json.readTree(response.body());
        assertThat(problem.path("code").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(problem.path("traceId").asText()).isNotBlank();
    }
}
