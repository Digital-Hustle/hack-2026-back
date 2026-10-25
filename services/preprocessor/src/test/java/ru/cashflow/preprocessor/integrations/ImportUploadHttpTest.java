package ru.cashflow.preprocessor.integrations;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"preprocessor.imports.max-file-bytes=10000", "preprocessor.imports.max-rows=5"})
class ImportUploadHttpTest {

    private static final String COMPANY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ACCOUNT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String USER = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String KEY = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String CSV_HEADER = "company_id;operation_id;operation_date;amount;direction;"
            + "counterparty_inn;counterparty_name;payment_purpose;balance_eod\n";
    private static final String CSV_ROW = "C001;A;2026-09-30;10.00;Поступление;;Клиент;Оплата;10.00\n";
    private static final OrchestratorStub ORCHESTRATOR = OrchestratorStub.start();

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void orchestratorProperties(DynamicPropertyRegistry registry) {
        registry.add("preprocessor.orchestrator.base-url", ORCHESTRATOR::baseUrl);
    }

    @AfterAll
    static void stopOrchestrator() {
        ORCHESTRATOR.close();
    }

    @BeforeEach
    void resetStub() {
        ORCHESTRATOR.reset();
    }

    @Test
    void importsRealXlsxThroughPublicEndpointAndFinalizes() throws Exception {
        byte[] excel;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Payments");
            var header = sheet.createRow(0);
            String[] keys = CSV_HEADER.strip().split(";");
            for (int index = 0; index < keys.length; index++) {
                header.createCell(index).setCellValue(keys[index]);
            }
            var row = sheet.createRow(1);
            String[] data = CSV_ROW.strip().split(";", -1);
            for (int index = 0; index < data.length; index++) {
                row.createCell(index).setCellValue(data[index]);
            }
            workbook.write(output);
            excel = output.toByteArray();
        }
        var boundary = "preprocessor-excel-upload";
        var prefix = new StringBuilder();
        for (var field : completeFields().entrySet()) {
            prefix.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(field.getKey())
                    .append("\"\r\n\r\n").append(field.getValue()).append("\r\n");
        }
        prefix.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"statement.xlsx\"\r\n")
                .append("Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n");
        var payload = new ByteArrayOutputStream();
        payload.write(prefix.toString().getBytes(StandardCharsets.UTF_8));
        payload.write(excel);
        payload.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri("/api/v1/preprocessor/companies/" + COMPANY + "/imports"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-User-Id", USER)
                .header("Idempotency-Key", KEY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload.toByteArray()))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("COMMITTED");
        assertThat(ORCHESTRATOR.stageRequest().queryParam("fileName")).isEqualTo("statement.xlsx");
        assertThat(ORCHESTRATOR.stageRequest().body()).contains("\"operations\":");
        assertThat(ORCHESTRATOR.finalizeCalls()).isEqualTo(1);
    }

    @Test
    void consumesPublicImportContractAndUsesStageEtagForFinalization() throws Exception {
        ORCHESTRATOR.publicContract(true);
        var response = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("status").asText()).isEqualTo("COMMITTED");
        assertThat(body.path("acceptedRows").asInt()).isEqualTo(1);
        assertThat(body.path("dataVersion").asLong()).isEqualTo(1);
        assertThat(ORCHESTRATOR.finalizeRequest().header("If-Match"))
                .isEqualTo("\"" + OrchestratorStub.STAGE_VERSION + "\"");
    }

    @Test
    void missingPublicStageEtagNeverGuessesAVersionOrFinalizes() throws Exception {
        ORCHESTRATOR.publicContract(false);
        var response = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(ORCHESTRATOR.finalizeCalls()).isZero();
    }

    @Test
    void stagesPreparedStatementThenFinalizesAndReturnsCommittedImport() throws Exception {
        var response = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("id").asText()).isEqualTo(OrchestratorStub.IMPORT_ID.toString());
        assertThat(body.path("companyId").asText()).isEqualTo(COMPANY);
        assertThat(body.path("accountId").asText()).isEqualTo(ACCOUNT);
        assertThat(body.path("status").asText()).isEqualTo("COMMITTED");
        assertThat(body.path("fileName").asText()).isEqualTo("statement.csv");
        assertThat(body.path("fileSha256").asText()).matches("^[0-9a-f]{64}$");
        assertThat(body.path("totalRows").asInt()).isEqualTo(1);
        assertThat(body.path("acceptedRows").asInt()).isEqualTo(1);
        assertThat(body.path("warningCount").asInt()).isEqualTo(1);

        var stage = ORCHESTRATOR.stageRequest();
        assertThat(stage.method()).isEqualTo("POST");
        assertThat(stage.path()).isEqualTo("/api/v1/orchestrator/companies/" + COMPANY + "/accounts/" + ACCOUNT
                + "/imports");
        assertThat(stage.header("X-User-Id")).isEqualTo(USER);
        assertThat(stage.header("Idempotency-Key")).isEqualTo(KEY);
        assertThat(stage.queryParam("fileName")).isEqualTo("statement.csv");
        assertThat(stage.queryParam("fileSha256")).isEqualTo(body.path("fileSha256").asText());
        assertThat(stage.queryParam("validationMode")).isEqualTo("SKIP_INVALID");
        assertThat(stage.queryParam("completeness")).isEqualTo("CONFIRMED_COMPLETE");
        assertThat(stage.queryParam("declaredFromDate")).isEqualTo("2026-01-01");
        assertThat(stage.queryParam("declaredToDate")).isEqualTo("2026-01-31");
        assertThat(stage.body()).contains("\"schemaVersion\":\"1\"", "\"companyId\":\"C001\"", "\"operations\":");

        var finalize = ORCHESTRATOR.finalizeRequest();
        assertThat(finalize.path()).endsWith("/imports/" + OrchestratorStub.IMPORT_ID + "/finalize");
        assertThat(finalize.header("X-User-Id")).isEqualTo(USER);
        assertThat(finalize.header("If-Match")).isEqualTo("\"" + OrchestratorStub.STAGE_VERSION + "\"");
        assertThat(ORCHESTRATOR.finalizeCalls()).isEqualTo(1);
        assertThat(ORCHESTRATOR.cancelCalls()).isZero();
    }

    @Test
    void countsOnlyInsertedRowsAsAcceptedAndAlreadyPresentAsDuplicates() throws Exception {
        ORCHESTRATOR.finalizedRows(0, 1);

        var response = send(COMPANY, completeFields(), "statement.csv",
                CSV_HEADER + CSV_ROW + CSV_ROW, defaultHeaders());

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("totalRows").asInt()).isEqualTo(2);
        assertThat(body.path("acceptedRows").asInt()).isZero();
        assertThat(body.path("duplicateRows").asInt()).isEqualTo(2);
        assertThat(body.path("rejectedRows").asInt()).isZero();
        assertThat(body.path("warningCount").asInt()).isEqualTo(2);
    }

    @Test
    void doesNotCountParserErrorsAsWarnings() throws Exception {
        String invalidAmount = "C001;BAD;2026-09-30;invalid;Поступление;;Клиент;Оплата;10.00\n";
        var response = send(COMPANY, completeFields(), "statement.csv",
                CSV_HEADER + CSV_ROW + invalidAmount, defaultHeaders());

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("totalRows").asInt()).isEqualTo(2);
        assertThat(body.path("acceptedRows").asInt()).isEqualTo(1);
        assertThat(body.path("rejectedRows").asInt()).isEqualTo(1);
        assertThat(body.path("warningCount").asInt()).isEqualTo(1);
    }

    @Test
    void repeatedIdempotencyKeyReturnsCommittedImportWithoutSecondFinalize() throws Exception {
        ORCHESTRATOR.stageAlreadyCommitted();

        var response = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("COMMITTED");
        assertThat(ORCHESTRATOR.requests()).hasSize(1);
        assertThat(ORCHESTRATOR.finalizeCalls()).isZero();
    }

    @Test
    void proxiesConflictAndUnprocessableFromStagingWithoutFinalizing() throws Exception {
        ORCHESTRATOR.stageProblem(409, "CONFLICT", "Импорт с этим ключом уже выполняется");
        var conflict = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(json.readTree(conflict.body()).path("code").asText()).isEqualTo("CONFLICT");
        assertThat(json.readTree(conflict.body()).path("detail").asText()).contains("уже выполняется");
        assertThat(ORCHESTRATOR.finalizeCalls()).isZero();

        ORCHESTRATOR.reset();
        ORCHESTRATOR.stageProblem(422, "IMPORT_REJECTED", "ATOMIC: есть отклонённые строки");
        var rejected = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());
        assertThat(rejected.statusCode()).isEqualTo(422);
        assertThat(json.readTree(rejected.body()).path("code").asText()).isEqualTo("IMPORT_REJECTED");
        assertThat(ORCHESTRATOR.finalizeCalls()).isZero();

        ORCHESTRATOR.reset();
        ORCHESTRATOR.stageProblem(503, "DEPENDENCY_UNAVAILABLE", "Orchestrator перегружен");
        var unavailable = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());
        assertThat(unavailable.statusCode()).isEqualTo(503);
        assertThat(json.readTree(unavailable.body()).path("code").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void keepsStagedImportOnFinalizeErrorAndProxiesTheProblem() throws Exception {
        ORCHESTRATOR.finalizeProblem(409, "VERSION_CONFLICT", "Версия импорта устарела");

        var response = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(json.readTree(response.body()).path("detail").asText()).isEqualTo("Версия импорта устарела");
        assertThat(ORCHESTRATOR.cancelCalls()).isZero();
        assertThat(ORCHESTRATOR.requests().stream().filter(r -> "GET".equals(r.method())).count()).isEqualTo(1);

        ORCHESTRATOR.reset();
        ORCHESTRATOR.finalizeReturns("STAGED");
        var incomplete = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());
        assertThat(incomplete.statusCode()).isEqualTo(409);
        assertThat(json.readTree(incomplete.body()).path("code").asText()).isEqualTo("IMPORT_INCOMPLETE");
        assertThat(ORCHESTRATOR.cancelCalls()).isZero();
    }

    @Test
    void preservesStructuredDownstreamProblemAndRealHttpStatus() throws Exception {
        ORCHESTRATOR.stageStructuredProblem();

        var response = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.path("traceId").asText()).isEqualTo("downstream-trace");
        assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("accountId");
        assertThat(body.path("retryAfterSec").asInt()).isEqualTo(7);
        assertThat(body.path("resourceId").asText()).isEqualTo(OrchestratorStub.IMPORT_ID.toString());
        assertThat(ORCHESTRATOR.finalizeCalls()).isZero();
    }

    @Test
    void recoversCommittedImportAfterFinalizeFailure() throws Exception {
        ORCHESTRATOR.finalizeProblem(503, "DEPENDENCY_UNAVAILABLE", "finalize timed out");
        ORCHESTRATOR.recoveryReturns("FINALIZED");

        var response = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("COMMITTED");
        assertThat(ORCHESTRATOR.cancelCalls()).isZero();
        assertThat(ORCHESTRATOR.requests().stream().filter(r -> "GET".equals(r.method())).count()).isEqualTo(1);
    }

    @Test
    void requiresExactlyOneCanonicalTrustedUser() throws Exception {
        var missing = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW,
                List.<String[]>of(header("Idempotency-Key", KEY)));
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(json.readTree(missing.body()).path("code").asText()).isEqualTo("AUTH_REQUIRED");

        var duplicate = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW,
                List.of(header("X-User-Id", USER), header("X-User-Id", USER), header("Idempotency-Key", KEY)));
        assertThat(duplicate.statusCode()).isEqualTo(401);

        var notCanonical = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW,
                List.of(header("X-User-Id", "1-1-1-1-1"), header("Idempotency-Key", KEY)));
        assertThat(notCanonical.statusCode()).isEqualTo(401);
        assertThat(ORCHESTRATOR.requests()).isEmpty();
    }

    @Test
    void rejectsMalformedFieldsLocallyWithoutCallingOrchestrator() throws Exception {
        var missingKey = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW,
                List.<String[]>of(header("X-User-Id", USER)));
        assertThat(missingKey.statusCode()).isEqualTo(400);
        assertThat(json.readTree(missingKey.body()).path("code").asText()).isEqualTo("MALFORMED_REQUEST");

        var badKey = send(COMPANY, completeFields(), "statement.csv", CSV_HEADER + CSV_ROW,
                List.<String[]>of(header("X-User-Id", USER), header("Idempotency-Key", "not-a-uuid")));
        assertThat(badKey.statusCode()).isEqualTo(400);

        var noFile = send(COMPANY, completeFields(), null, null, defaultHeaders());
        assertThat(noFile.statusCode()).isEqualTo(400);
        assertThat(json.readTree(noFile.body()).path("errors").get(0).path("code").asText())
                .isEqualTo("FILE_REQUIRED");

        var badAccount = completeFields();
        badAccount.put("accountId", "not-a-uuid");
        var account = send(COMPANY, badAccount, "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());
        assertThat(account.statusCode()).isEqualTo(400);
        assertThat(json.readTree(account.body()).path("errors").get(0).path("code").asText()).isEqualTo("INVALID_UUID");

        var badMode = completeFields();
        badMode.put("validationMode", "MAYBE");
        assertThat(send(COMPANY, badMode, "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders()).statusCode())
                .isEqualTo(400);

        var badCompany = send("not-a-uuid", completeFields(), "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());
        assertThat(badCompany.statusCode()).isEqualTo(400);
        assertThat(json.readTree(badCompany.body()).path("code").asText()).isEqualTo("MALFORMED_REQUEST");
        assertThat(ORCHESTRATOR.requests()).isEmpty();
    }

    @Test
    void completenessAndDatesAreOptionalAndOnlyAReversedRangeIsRejected() throws Exception {
        var reversed = completeFields();
        reversed.put("declaredFromDate", "2026-02-01");
        reversed.put("declaredToDate", "2026-01-01");
        var range = send(COMPANY, reversed, "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());
        assertThat(range.statusCode()).isEqualTo(422);
        assertThat(json.readTree(range.body()).path("errors").get(0).path("code").asText())
                .isEqualTo("INVALID_DATE_RANGE");
        assertThat(ORCHESTRATOR.requests()).isEmpty();
    }

    @Test
    void omittedCompletenessAndDatesAreNotSentSoOrchestratorConfirmsThePeriod() throws Exception {
        var fields = completeFields();
        fields.remove("completeness");
        fields.remove("declaredFromDate");
        fields.remove("declaredToDate");

        var response = send(COMPANY, fields, "statement.csv", CSV_HEADER + CSV_ROW, defaultHeaders());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var stage = ORCHESTRATOR.stageRequest();
        assertThat(stage.queryParam("completeness")).isNull();
        assertThat(stage.queryParam("declaredFromDate")).isNull();
        assertThat(stage.queryParam("declaredToDate")).isNull();
    }

    @Test
    void appliesContractFileAndRowLimits() throws Exception {
        var wrongType = send(COMPANY, completeFields(), "statement.pdf", CSV_HEADER + CSV_ROW, defaultHeaders());
        assertThat(wrongType.statusCode()).isEqualTo(415);
        assertThat(json.readTree(wrongType.body()).path("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");

        var oversized = send(COMPANY, completeFields(), "statement.csv", "a".repeat(10_001), defaultHeaders());
        assertThat(oversized.statusCode()).isEqualTo(413);
        assertThat(json.readTree(oversized.body()).path("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");

        StringBuilder manyRows = new StringBuilder(CSV_HEADER);
        for (int i = 0; i < 6; i++) {
            manyRows.append("C001;A").append(i).append(";2026-09-30;10.00;Поступление;;Клиент;Оплата;10.00\n");
        }
        var tooMany = send(COMPANY, completeFields(), "statement.csv", manyRows.toString(), defaultHeaders());
        assertThat(tooMany.statusCode()).isEqualTo(413);
        assertThat(json.readTree(tooMany.body()).path("errors").get(0).path("code").asText())
                .isEqualTo("FILE_TOO_LARGE");
        assertThat(ORCHESTRATOR.requests()).isEmpty();
    }

    private Map<String, String> completeFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("accountId", ACCOUNT);
        fields.put("validationMode", "SKIP_INVALID");
        fields.put("completeness", "CONFIRMED_COMPLETE");
        fields.put("declaredFromDate", "2026-01-01");
        fields.put("declaredToDate", "2026-01-31");
        return fields;
    }

    private List<String[]> defaultHeaders() {
        return List.of(header("X-User-Id", USER), header("Idempotency-Key", KEY));
    }

    private static String[] header(String name, String value) {
        return new String[] {name, value};
    }

    private HttpResponse<String> send(String companyId, Map<String, String> fields, String filename, String content,
                                      List<String[]> headers) throws IOException, InterruptedException {
        String boundary = "preprocessor-import-boundary";
        StringBuilder body = new StringBuilder();
        for (var entry : fields.entrySet()) {
            body.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"").append(entry.getKey()).append("\"\r\n\r\n")
                    .append(entry.getValue()).append("\r\n");
        }
        if (filename != null) {
            body.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(filename)
                    .append("\"\r\n")
                    .append("Content-Type: text/csv\r\n\r\n").append(content).append("\r\n");
        }
        body.append("--").append(boundary).append("--\r\n");
        var builder = HttpRequest.newBuilder(uri("/api/v1/preprocessor/companies/" + companyId + "/imports"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        for (String[] header : headers) {
            builder.header(header[0], header[1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
