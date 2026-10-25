package ru.cashflow.preprocessor.integrations;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"preprocessor.limits.statement-bytes=10000", "preprocessor.limits.max-issues=5"})
@ExtendWith(OutputCaptureExtension.class)
class PrepareStatementHttpTest {

    private static final String HEADER = "company_id;operation_id;operation_date;amount;direction;counterparty_inn;"
            + "counterparty_name;payment_purpose;balance_eod\n";
    private static final String PATH = "/internal/v1/statements/prepare";
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void producesCompleteJsonWithMoneyAsStringsAndNoSensitiveConsoleLogs(CapturedOutput output) throws Exception {
        String purpose = "Зачисление средств по операциям эквайринга. Private-purpose-needle";
        var response = send("statement.csv", HEADER
                + "C001;A;2026-09-30;123456.78;Поступление;0012345678;Private-counterparty-needle;" + purpose + ";987654.32\n");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = json.readTree(response.body());
        JsonNode operation = result.path("operations").get(0);
        assertThat(operation.path("amount").isTextual()).isTrue();
        assertThat(operation.path("amount").asText()).isEqualTo("123456.78");
        assertThat(operation.path("balanceEod").asText()).isEqualTo("987654.32");
        assertThat(operation.path("paymentPurpose").asText()).isEqualTo(purpose);
        assertThat(operation.path("counterpartyInn").asText()).isEqualTo("0012345678");
        assertThat(operation.path("category").path("code").asText()).isEqualTo("REVENUE_ACQUIRING");
        assertThat(operation.path("sourceFields").path("direction").asText()).isEqualTo("Поступление");
        assertThat(operation.has("regular")).isFalse();
        assertThat(operation.has("recurring")).isFalse();
        assertThat(result.path("report").path("status").asText()).isEqualTo("PREPARED");
        assertThat(result.has("persisted")).isFalse();
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(output.getAll()).contains("statement_preparation_completed")
                .contains("rowsAccepted=1", "rowsRejected=0")
                .doesNotContain("123456.78", "987654.32", "0012345678", "Private-purpose-needle", "Private-counterparty-needle");
    }

    @Test
    void returnsRowValidationReportWithoutCallingAnExternalService() throws Exception {
        var response = send("statement.csv", HEADER
                + "C001;A;2026-09-30;-1.00;Списание;;Банк;Текст;0.00\n");

        assertThat(response.statusCode()).isEqualTo(200);
        var result = json.readTree(response.body());
        assertThat(result.path("report").path("status").asText()).isEqualTo("INVALID");
        assertThat(result.path("report").path("rowsRejected").asInt()).isEqualTo(1);
        assertThat(result.path("report").path("issues").get(0).path("field").asText()).isEqualTo("amount");
    }

    @Test
    void returnsSafeProblemForBrokenFile(CapturedOutput output) throws Exception {
        String secret = "sensitive-file-content@example.test";
        var response = send("statement.csv", secret);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("MISSING_COLUMNS");
        assertThat(response.body()).doesNotContain(secret);
        assertThat(output.getAll()).doesNotContain(secret);
        assertThat(output.getAll()).contains("statement_preparation_rejected", "MISSING_COLUMNS");
    }

    @Test
    void rejectsWrongExtensionAndOversizedUpload() throws Exception {
        assertThat(send("statement.pdf", HEADER).statusCode()).isEqualTo(415);
        assertThat(send("statement.csv", "a".repeat(10001)).statusCode()).isEqualTo(413);
    }

    @Test
    void rejectsMissingFileAndDoesNotExposePublicImportOrDebugEndpoints() throws Exception {
        var request = HttpRequest.newBuilder(uri(PATH)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
        for (String path : new String[] {"/api/v1/preprocessor/imports", "/actuator/env"}) {
            var response = client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(404);
        }
        var health = client.send(HttpRequest.newBuilder(uri("/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(json.readTree(health.body()).path("status").asText()).isEqualTo("UP");
    }

    @Test
    void attachesOptionalCompanyCsvToPreparedJson() throws Exception {
        String boundary = "preprocessor-company-boundary";
        String statement = HEADER + "C001;A;2026-09-30;10.00;Поступление;;Клиент;Оплата;10.00\n";
        String companies = "company_id;company_name;company_inn;legal_form;industry;first_operation_date;"
                + "last_operation_date;operations_count\n"
                + "C001;Компания;0012345678;ООО;Услуги;2026-09-30;2026-09-30;1\n";
        String body = part(boundary, "file", "statement.csv", statement)
                + part(boundary, "companies", "companies.csv", companies) + "--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder(uri(PATH)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        var company = json.readTree(response.body()).path("companies").get(0);
        assertThat(company.path("companyName").asText()).isEqualTo("Компания");
        assertThat(company.path("companyInn").asText()).isEqualTo("0012345678");
        assertThat(company.path("sourceFields").path("operations_count").asText()).isEqualTo("1");
    }

    @Test
    void reportsMissingMultipartFileAsBadRequest() throws Exception {
        String boundary = "preprocessor-missing-file-boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\nhello"
                + "\r\n--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder(uri(PATH)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("FILE_REQUIRED");
    }

    private String part(String boundary, String name, String filename, String contents) {
        return "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename
                + "\"\r\nContent-Type: text/csv\r\n\r\n" + contents + "\r\n";
    }

    private HttpResponse<String> send(String filename, String contents) throws IOException, InterruptedException {
        String boundary = "preprocessor-test-boundary";
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: text/csv\r\n\r\n" + contents + "\r\n--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder(uri(PATH)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
