package ru.cashflow.forecast.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import ru.cashflow.forecast.api.json.SnapshotFingerprint;
import ru.cashflow.forecast.units.TestInputs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ForecastHttpTest.FixedTime.class)
@ExtendWith(OutputCaptureExtension.class)
class ForecastHttpTest {
    @LocalServerPort private int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void calculateReturnsMoneyStringsThirtyDaysAndRequestedIdentityWithoutJwt() throws Exception {
        var response = post("calculate", TestInputs.fixture("calculate.json").toString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = TestInputs.JSON.readTree(response.body());
        assertThat(body.path("id").asText()).isEqualTo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        assertThat(body.path("jobId").asText()).isEqualTo("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        assertThat(body.path("snapshotId").asText())
                .isEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(body.path("days")).hasSize(30);
        assertThat(body.path("expectedMinimumBalance").asText()).isEqualTo("-50000.00");
        assertThat(body.path("expectedMinimumBalance").isTextual()).isTrue();
        assertThat(body.path("riskEstimate").path("probability").isNull()).isTrue();
        assertThat(body.path("days").get(29).path("balance").asText()).isEqualTo("0.00");
        assertThat(body.path("createdAt").asText()).isEqualTo("2026-10-25T10:00:00Z");
    }

    @Test
    void backtestAndDetectionExposeSynchronousResultsNotJobs() throws Exception {
        var backtest = post("backtest", TestInputs.fixture("backtest.json").toString());
        assertThat(backtest.statusCode()).isEqualTo(200);
        var body = TestInputs.JSON.readTree(backtest.body());
        assertThat(body.path("maeBalance").asText()).isEqualTo("31000.00");
        assertThat(body.path("origins")).hasSize(1);
        assertThat(body.path("status").asText()).isEqualTo("COMPLETE");
        var detection =
                post(
                        "recurring-detection",
                        TestInputs.fixture("recurring-detection.json").toString());
        assertThat(detection.statusCode()).isEqualTo(200);
        assertThat(TestInputs.JSON.readTree(detection.body()).path("patterns")).isEmpty();
    }

    @Test
    void currentKnowledgeAllowsNewVersionZeroPlansAndLateImportedCompleteness() throws Exception {
        ObjectNode node = TestInputs.fixture("calculate.json");
        node.put("knowledgeCutoff", "2026-10-25T09:00:00Z");
        ((ObjectNode) node.withArray("completeRanges").get(0))
                .put("createdAt", "2026-10-25T08:00:00Z");
        ObjectNode plan = (ObjectNode) node.withArray("knownPlans").get(0);
        plan.put("version", 0);
        plan.put("createdAt", "2026-10-25T08:00:00Z");
        plan.put("knownAt", "2026-10-25T08:00:00Z");
        plan.put("updatedAt", "2026-10-25T08:00:00Z");
        node.put("fingerprint", SnapshotFingerprint.compute(node));
        var response = post("calculate", node.toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(
                        TestInputs.JSON
                                .readTree(response.body())
                                .path("days")
                                .get(4)
                                .path("balance")
                                .asText())
                .isEqualTo("-50000.00");
        plan.put("dueDate", node.path("asOfDate").asText());
        plan.put("overdue", false);
        node.put("fingerprint", SnapshotFingerprint.compute(node));
        response = post("calculate", node.toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(
                        TestInputs.JSON
                                .readTree(response.body())
                                .path("days")
                                .get(29)
                                .path("balance")
                                .asText())
                .isEqualTo("160000.00");
        plan.put("knownAt", "2026-10-25T09:00:00Z");
        node.put("fingerprint", SnapshotFingerprint.compute(node));
        assertProblem(post("calculate", node.toString()), 422, "VALIDATION_FAILED");
    }

    @Test
    void rejectsCalibrationInputWithKnowledgeFromAfterItsHistoricalDay() throws Exception {
        ObjectNode outer = TestInputs.fixture("calculate.json");
        ObjectNode origin = TestInputs.fixture("calibration-origin.json");
        ObjectNode nested = (ObjectNode) origin.get("input");
        nested.put("knowledgeCutoff", "2026-08-03T09:00:00Z");
        nested.put("fingerprint", SnapshotFingerprint.compute(nested));
        outer.withArray("calibrationOrigins").add(origin);
        outer.put("fingerprint", SnapshotFingerprint.compute(outer));
        assertProblem(post("calculate", outer.toString()), 422, "VALIDATION_FAILED");
    }

    @Test
    void historicalKnowledgeBoundaryUsesSnapshotTimezoneInsteadOfMoscow() throws Exception {
        ObjectNode outer = TestInputs.fixture("calculate.json");
        ObjectNode origin = TestInputs.fixture("calibration-origin.json");
        ObjectNode nested = (ObjectNode) origin.get("input");
        nested.put("timezone", "UTC");
        nested.put("knowledgeCutoff", "2026-08-02T00:00:00Z");
        ((ObjectNode) nested.withArray("completeRanges").get(0))
                .put("createdAt", "2026-08-01T22:00:00Z");
        nested.put("fingerprint", SnapshotFingerprint.compute(nested));
        outer.withArray("calibrationOrigins").add(origin);
        outer.put("fingerprint", SnapshotFingerprint.compute(outer));
        var response = post("calculate", outer.toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        nested.put("knowledgeCutoff", "2026-08-02T00:00:00.000001Z");
        nested.put("fingerprint", SnapshotFingerprint.compute(nested));
        outer.put("fingerprint", SnapshotFingerprint.compute(outer));
        assertProblem(post("calculate", outer.toString()), 422, "VALIDATION_FAILED");
    }

    @Test
    void rejectsInvalidOrFutureKnowledgeAndLateCompletenessWithoutWeakeningHistoricalBacktest()
            throws Exception {
        for (String field : java.util.List.of("knowledgeCutoff", "timezone")) {
            ObjectNode node = TestInputs.fixture("calculate.json");
            node.remove(field);
            assertProblem(post("calculate", node.toString()), 422, "VALIDATION_FAILED");
        }
        ObjectNode node = TestInputs.fixture("calculate.json");
        node.put("knowledgeCutoff", "2026-10-26T00:00:00Z");
        node.put("fingerprint", SnapshotFingerprint.compute(node));
        assertProblem(post("calculate", node.toString()), 422, "VALIDATION_FAILED");
        node = TestInputs.fixture("calculate.json");
        node.put("timezone", "Invalid/Zone");
        node.put("fingerprint", SnapshotFingerprint.compute(node));
        assertProblem(post("calculate", node.toString()), 422, "VALIDATION_FAILED");
        node = TestInputs.fixture("calculate.json");
        ((ObjectNode) node.withArray("completeRanges").get(0))
                .put("createdAt", node.path("knowledgeCutoff").asText());
        node.put("fingerprint", SnapshotFingerprint.compute(node));
        assertProblem(post("calculate", node.toString()), 422, "VALIDATION_FAILED");
        ObjectNode backtest = TestInputs.fixture("backtest.json");
        ObjectNode origin = (ObjectNode) backtest.withArray("originSnapshots").get(0);
        origin.put("knowledgeCutoff", "2026-09-02T12:00:00Z");
        origin.put("fingerprint", SnapshotFingerprint.compute(origin));
        assertProblem(post("backtest", backtest.toString()), 422, "VALIDATION_FAILED");
    }

    @Test
    void malformedJsonAndUnknownFieldsHaveDifferentSafeProblemResponses(CapturedOutput output)
            throws Exception {
        var malformed = post("calculate", "{\"startBalance\": \"PRIVATE_FINANCIAL_MARKER\"");
        assertProblem(malformed, 400, "MALFORMED_REQUEST");
        ObjectNode unknown = TestInputs.fixture("calculate.json");
        unknown.put("privateSecretField", "PRIVATE_FINANCIAL_MARKER");
        var response = post("calculate", unknown.toString());
        assertProblem(response, 422, "VALIDATION_FAILED");
        assertThat(response.body()).doesNotContain("PRIVATE_FINANCIAL_MARKER");
        assertThat(output.getAll()).doesNotContain("PRIVATE_FINANCIAL_MARKER");
        assertThat(output.getAll())
                .contains(TestInputs.JSON.readTree(response.body()).path("traceId").asText());
    }

    @Test
    void validationLogsReportFieldAndTraceWithoutFinancialRequest(CapturedOutput output) throws Exception {
        ObjectNode node = TestInputs.fixture("calculate.json");
        ObjectNode plan = (ObjectNode) node.withArray("knownPlans").get(0);
        plan.put("version", -1);
        plan.put("note", "PRIVATE_FINANCIAL_MARKER");
        var response = post("calculate", node.toString());
        assertProblem(response, 422, "VALIDATION_FAILED");
        String traceId = TestInputs.JSON.readTree(response.body()).path("traceId").asText();
        var rejection = output.getAll().lines().filter(line -> line.contains("forecast_request_rejected")
                && line.contains(traceId)).toList();
        assertThat(rejection).singleElement().asString().contains("status=422", "code=VALIDATION_FAILED",
                "fields=[knownPlans[0].version]");
        assertThat(output.getAll()).doesNotContain("PRIVATE_FINANCIAL_MARKER", "160000.00");
    }

    @Test
    void fingerprintIsVerifiedAndNestedCalibrationCannotRecurse() throws Exception {
        ObjectNode changed = TestInputs.fixture("calculate.json");
        changed.put("startBalance", "1.00");
        assertProblem(post("calculate", changed.toString()), 422, "VALIDATION_FAILED");
        ObjectNode outer = TestInputs.fixture("calculate.json");
        ObjectNode origin = TestInputs.fixture("calibration-origin.json");
        ObjectNode nested = (ObjectNode) origin.get("input");
        nested.withArray("calibrationOrigins").add(TestInputs.fixture("calibration-origin.json"));
        nested.put("fingerprint", SnapshotFingerprint.compute(nested));
        outer.withArray("calibrationOrigins").add(origin);
        outer.put("fingerprint", SnapshotFingerprint.compute(outer));
        assertProblem(post("calculate", outer.toString()), 422, "VALIDATION_FAILED");
    }

    @Test
    void unknownEnumNumericMoneyMissingNullableAndWrongMoneyScaleAre422() throws Exception {
        for (String variant : java.util.List.of("enum", "numeric", "missing", "scale")) {
            ObjectNode node = TestInputs.fixture("calculate.json");
            switch (variant) {
                case "enum" -> node.put("scope", "UNKNOWN");
                case "numeric" -> node.put("startBalance", 100000);
                case "missing" ->
                        ((ObjectNode) node.withArray("payments").get(0)).remove("counterpartyKey");
                case "scale" -> node.put("startBalance", "100000.001");
                default -> throw new IllegalArgumentException();
            }
            assertProblem(post("calculate", node.toString()), 422, "VALIDATION_FAILED");
        }
    }

    @Test
    void rejectsUserCredentialsAndMissingJobHeaders() throws Exception {
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/internal/v1/forecast/calculate"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer PRIVATE_TOKEN_MARKER")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        TestInputs.fixture("calculate.json").toString()))
                        .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertProblem(response, 403, "FORBIDDEN");
        assertThat(response.body()).doesNotContain("PRIVATE_TOKEN_MARKER");
        var noHeaders =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/internal/v1/forecast/calculate"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build();
        assertProblem(
                client.send(noHeaders, HttpResponse.BodyHandlers.ofString()),
                400,
                "MALFORMED_REQUEST");
    }

    @Test
    void repeatedSnapshotKeepsNumericalResultAndMissingWeekdayDoesNotReturnFakeForecast()
            throws Exception {
        String request = TestInputs.fixture("calculate.json").toString();
        assertThat(post("calculate", request).body()).isEqualTo(post("calculate", request).body());
        ObjectNode shortHistory =
                TestInputs.synthetic(java.time.LocalDate.of(2026, 10, 24), 6, "1.00", "0.00");
        shortHistory.put("fingerprint", SnapshotFingerprint.compute(shortHistory));
        assertProblem(post("calculate", shortHistory.toString()), 422, "INSUFFICIENT_HISTORY");
    }

    @Test
    void matureNestedOriginsProduceAvailableRiskOverHttpAndMatureOnlyAtEachOuterCutoff()
            throws Exception {
        ObjectNode outer = TestInputs.fixture("calculate.json");
        for (int index = 0; index < 12; index++) {
            var date = java.time.LocalDate.of(2026, 10, 24).minusDays(30L + index * 7L);
            var input = TestInputs.synthetic(date, 7, "10000.00", "8000.00");
            input.put("scope", "WITH_KNOWN_PLANS");
            input.put("fingerprint", SnapshotFingerprint.compute(input));
            var origin = outer.withArray("calibrationOrigins").addObject();
            origin.set("input", input);
            var outcomes = origin.putArray("actualDays");
            for (int horizon = 1; horizon <= 30; horizon++) {
                var day = outcomes.addObject();
                day.put("date", date.plusDays(horizon).toString());
                day.put("inflow", "0.00");
                day.put("outflow", "0.00");
                day.put("balanceEod", "100000.00");
                day.put("paymentCount", 0);
                day.put("balanceMismatch", false);
                day.put("status", "ZERO_CONFIRMED");
            }
            origin.putArray("actualPayments");
        }
        outer.put("fingerprint", SnapshotFingerprint.compute(outer));
        var response = post("calculate", outer.toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var body = TestInputs.JSON.readTree(response.body());
        assertThat(body.path("riskEstimate").path("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(body.path("riskEstimate").path("probability").asDouble()).isEqualTo(1.0);
        assertThat(body.path("riskEstimate").path("scenarioCount").asInt()).isEqualTo(2000);
        assertThat(body.path("days").get(4).path("balanceP50").asText()).isEqualTo("-60000.00");
        var origin = (ObjectNode) outer.withArray("calibrationOrigins").get(0);
        var nested = (ObjectNode) origin.get("input");
        nested.put("asOfDate", "2026-10-01");
        nested.put("fingerprint", SnapshotFingerprint.compute(nested));
        outer.put("fingerprint", SnapshotFingerprint.compute(outer));
        assertProblem(post("calculate", outer.toString()), 422, "VALIDATION_FAILED");
    }

    @Test
    void duplicateKeysTrailingJsonAndExcessiveNestingAreRejectedAsMalformed() throws Exception {
        assertProblem(post("calculate", "{\"a\":1,\"a\":2}"), 400, "MALFORMED_REQUEST");
        assertProblem(post("calculate", "{}{}"), 400, "MALFORMED_REQUEST");
        assertProblem(
                post("calculate", "[".repeat(101) + "0" + "]".repeat(101)),
                400,
                "MALFORMED_REQUEST");
    }

    @Test
    void healthWorksWithoutJobHeadersButPublicForecastResourcesDoNotExist() throws Exception {
        var health =
                client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(TestInputs.JSON.readTree(health.body()).path("status").asText()).isEqualTo("UP");
        var publicRequest =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/api/v1/forecast/results"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertProblem(publicRequest, 404, "NOT_FOUND");
    }

    @Test
    void riskWarningsExplicitlyDistinguishUnavailableAndScenarioEstimate() throws Exception {
        var response = post("calculate", TestInputs.fixture("calculate.json").toString());
        var body = TestInputs.JSON.readTree(response.body());
        assertThat(body.path("warnings"))
                .anySatisfy(
                        warning ->
                                assertThat(warning.path("code").asText())
                                        .isEqualTo("RISK_UNAVAILABLE"));
    }

    private void assertProblem(HttpResponse<String> response, int status, String code)
            throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/problem+json");
        var body = TestInputs.JSON.readTree(response.body());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("traceId").asText()).isNotBlank();
        assertThat(body.has("errors")).isTrue();
        assertThat(body.has("retryAfterSec")).isTrue();
        assertThat(body.has("resourceId")).isTrue();
    }

    private HttpResponse<String> post(String endpoint, String body) throws Exception {
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + "/internal/v1/forecast/"
                                                + endpoint))
                        .header("Content-Type", "application/json")
                        .header("X-Calculation-Id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
                        .header("X-Job-Id", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
                        .header("Idempotency-Key", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class FixedTime {
        @Bean
        @Primary
        Clock clock() {
            return TestInputs.CLOCK;
        }
    }
}
