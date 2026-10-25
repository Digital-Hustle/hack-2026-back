package ru.cashflow.orchestrator.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.forecast.application.ForecastWorker;
import ru.cashflow.orchestrator.features.snapshot.application.SnapshotService;
import ru.cashflow.orchestrator.features.snapshot.command.SnapshotException;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/** Real PostgreSQL + both HTTP services; no Forecast stub or duplicated calculation. */
class RealForecastCompatibilityTest extends AbstractIntegrationTest {
    private static Process forecast;
    private static String forecastUrl;

    @Autowired ForecastWorker worker;
    @Autowired SnapshotRepository snapshots;
    @Autowired SnapshotService snapshotService;
    @Autowired CompanyAccess access;

    @DynamicPropertySource
    static void forecast(DynamicPropertyRegistry registry) {
        registry.add(
                "orchestrator.forecast.base-url", RealForecastCompatibilityTest::startForecast);
    }

    private static synchronized String startForecast() {
        if (forecast != null) {
            return forecastUrl;
        }
        try {
            Path jar = Path.of(System.getProperty("forecast.jar"));
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException(
                        "Build the Forecast bootJar before the contract test");
            }
            int port;
            try (var socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            Path log = Path.of("build", "real-forecast-test.log");
            forecast =
                    new ProcessBuilder(
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-jar",
                                    jar.toString(),
                                    "--server.port=" + port,
                                    "--server.address=127.0.0.1")
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile())
                            .start();
            forecastUrl = "http://127.0.0.1:" + port;
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            for (int attempt = 0; attempt < 150; attempt++) {
                if (!forecast.isAlive()) {
                    throw new IllegalStateException("Forecast exited; see " + log);
                }
                try {
                    var response =
                            client.send(
                                    HttpRequest.newBuilder(URI.create(forecastUrl + "/health"))
                                            .timeout(Duration.ofSeconds(1))
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() == 200) {
                        return forecastUrl;
                    }
                } catch (IOException ignored) {
                    // Bounded startup wait, no unbounded polling of external services.
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("Forecast startup timed out; see " + log);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start Forecast", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @AfterAll
    static void stopForecast() throws InterruptedException {
        if (forecast != null) {
            forecast.destroy();
            if (!forecast.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                forecast.destroyForcibly();
                forecast.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void employeeSalaryReachesRealForecastAndChangesDoNotRewriteOldSnapshots() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Employee payroll forecast");
        UUID account = createAccount(owner, company, null);
        String base = "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;
        String employees = "/api/v1/orchestrator/companies/" + company + "/employees";
        // Current knowledge with an older history date must also accept a newly entered manual salary.
        LocalDate cutoff = companyToday(company).minusDays(35);
        var complete = send("POST", base + "/completeness", owner,
                "{\"fromDate\":\"%s\",\"toDate\":\"%s\",\"completeness\":\"CONFIRMED_COMPLETE\",\"reason\":\"Test\"}"
                        .formatted(cutoff.minusDays(55), cutoff));
        assertThat(complete.status()).as(complete.raw()).isEqualTo(201);
        assertThat(send("POST", base + "/balance-anchors", owner,
                "{\"date\":\"%s\",\"balance\":\"100000.00\",\"reason\":\"Test\"}".formatted(cutoff)).status())
                .isEqualTo(201);
        var employee = send("POST", employees, owner, "{\"fullName\":\"Payroll test\"}",
                "Idempotency-Key", "payroll-real-employee");
        assertThat(employee.status()).as(employee.raw()).isEqualTo(201);
        String employeePath = employees + "/" + employee.body().path("id").asText();
        String salary = "{\"accountId\":\"%s\",\"amount\":\"10000.05\",\"counterpartyInn\":\"123456789012\","
                .formatted(account) + "\"schedule\":{\"frequency\":\"WEEKLY\",\"weekday\":"
                + cutoff.plusDays(1).getDayOfWeek().getValue() + ",\"businessDayRule\":\"NONE\"}}";
        var assigned = send("PUT", employeePath + "/salary", owner, salary, "If-Match", "\"1\"");
        assertThat(assigned.status()).as(assigned.raw()).isEqualTo(200);
        var firstJob = payrollJob(owner, company, base, cutoff, "payroll-real-forecast-1");
        String snapshotId = firstJob.path("snapshotId").asText();
        var scope = access.requireRead(owner, company);
        String original = snapshots.find(scope, UUID.fromString(snapshotId)).orElseThrow().payload();
        var first = payrollResult(owner, company, base, firstJob);
        assertThat(first.path("days").size()).isEqualTo(30);
        assertThat(first.path("days").get(29).path("balance").asText()).isEqualTo("49999.75");

        var updated = send("PUT", employeePath + "/salary", owner,
                salary.replace("10000.05", "20000.10").replace("}}", "},\"recurringPatternVersion\":0}"),
                "If-Match", "\"2\"");
        assertThat(updated.status()).as(updated.raw()).isEqualTo(200);
        var second = payrollResult(owner, company, base,
                payrollJob(owner, company, base, cutoff, "payroll-real-forecast-2"));
        assertThat(second.path("days").get(29).path("balance").asText()).isEqualTo("-0.50");
        assertThat(snapshots.find(scope, UUID.fromString(snapshotId)).orElseThrow().payload()).isEqualTo(original);
        assertThat(send("POST", employeePath + "/archive", owner, null, "If-Match", "\"3\"").status()).isEqualTo(200);
        var stopped = payrollResult(owner, company, base,
                payrollJob(owner, company, base, cutoff, "payroll-real-forecast-3"));
        assertThat(stopped.path("days").get(29).path("balance").asText()).isEqualTo("100000.00");
    }

    private com.fasterxml.jackson.databind.JsonNode payrollJob(UUID owner, UUID company, String base,
                                                               LocalDate cutoff, String key) throws Exception {
        var created = send("POST", base + "/forecast-jobs", owner,
                "{\"asOfDate\":\"%s\",\"scope\":\"WITH_KNOWN_PLANS\"}".formatted(cutoff),
                "Idempotency-Key", key);
        assertThat(created.status()).as(created.raw()).isEqualTo(202);
        return created.body();
    }

    private com.fasterxml.jackson.databind.JsonNode payrollResult(UUID owner, UUID company, String base,
                                                                  com.fasterxml.jackson.databind.JsonNode job) throws Exception {
        assertThat(worker.runOnce()).isTrue();
        var completed = send("GET", "/api/v1/orchestrator/companies/" + company + "/jobs/" + job.path("id").asText(),
                owner, null);
        assertThat(completed.body().path("status").asText()).as(completed.raw()).isEqualTo("SUCCEEDED");
        var result = send("GET", base + "/forecasts/" + completed.body().path("resultId").asText(), owner, null);
        assertThat(result.status()).as(result.raw()).isEqualTo(200);
        return result.body();
    }

    @Test
    void unknownCompletenessNeverInventsForecastStartBalance() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Unknown coverage");
        UUID account = createAccount(owner, company, "C001");
        String base = "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;
        LocalDate cutoff = LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).minusDays(1);

        var payload = json.createObjectNode();
        payload.put("schemaVersion", "1");
        payload.put("rulesVersion", "R1");
        payload.put("inputFingerprint", "b".repeat(64));
        payload.put("preparedAt", java.time.Instant.now().toString());
        payload.putArray("companies");
        var operation = payload.putArray("operations").addObject();
        operation.put("companyId", "C001");
        operation.put("sourceLine", 2);
        operation.put("counterpartyInn", "0068275202");
        operation.put("counterpartyName", "Test");
        operation.put("paymentPurpose", "Test");
        operation.put("operationId", "UNKNOWN-1");
        operation.put("operationDate", cutoff.toString());
        operation.put("amount", "100.00");
        operation.put("direction", "IN");
        operation.put("balanceEod", "1000.00");
        operation.putObject("category").put("code", "SALES").put("source", "RULE")
                .put("rulesVersion", "R1");
        payload.putArray("dailyBalances").addObject()
                .put("companyId", "C001")
                .put("date", cutoff.toString())
                .put("balanceEod", "1000.00")
                .put("balanceStatus", "OBSERVED");
        payload.putObject("report").put("status", "PREPARED").put("rowsTotal", 1)
                .put("rowsAccepted", 1).put("rowsRejected", 0)
                .put("rowsDuplicate", 0).put("issueCount", 0).put("warningCount", 0);

        var staged = send("POST", base + "/imports?completeness=UNKNOWN", owner, payload.toString(),
                "Idempotency-Key", "unknown-forecast-001");
        assertThat(staged.status()).as(staged.raw()).isEqualTo(201);
        var finalized = send("POST", base + "/imports/" + staged.body().path("id").asText() + "/finalize",
                owner, null, "If-Match", "\"" + staged.body().path("version").asLong() + "\"");
        assertThat(finalized.status()).as(finalized.raw()).isEqualTo(200);
        assertThat(finalized.body().path("dataVersion").asLong()).isEqualTo(1);

        var history = send("GET", base + "/daily-history?from=" + cutoff + "&to=" + cutoff,
                owner, null);
        assertThat(history.status()).as(history.raw()).isEqualTo(200);
        assertThat(history.body().path("items").get(0).path("status").asText()).isEqualTo("MISSING");

        var forecast = send("POST", base + "/forecast-jobs", owner,
                "{\"asOfDate\":\"" + cutoff + "\",\"scope\":\"HISTORY_ONLY\"}",
                "Idempotency-Key", "unknown-forecast-job-001");
        assertThat(forecast.status()).as(forecast.raw()).isEqualTo(422);
        assertThat(forecast.body().path("code").asText()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(jdbc.sql("select count(*) from jobs where company_id = :company")
                .param("company", company).query(Long.class).single()).isZero();
    }

    @Test
    void importedHistoryAndNewPlansReachRealForecastAndPersistThirtyDayResult() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Real Forecast contract");
        UUID account = createAccount(owner, company, "C001");
        String base = "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;
        LocalDate cutoff = LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).minusDays(1);
        var payload = json.createObjectNode();
        payload.put("schemaVersion", "1");
        payload.put("rulesVersion", "R1");
        payload.put("inputFingerprint", "a".repeat(64));
        payload.put("preparedAt", java.time.Instant.now().toString());
        payload.putArray("companies");
        var operations = payload.putArray("operations");
        var balances = payload.putArray("dailyBalances");
        for (int offset = 6; offset >= 0; offset--) {
            LocalDate date = cutoff.minusDays(offset);
            for (String direction : java.util.List.of("IN", "OUT")) {
                var payment = operations.addObject();
                payment.put("sourceLine", operations.size() + 1);
                payment.put("companyId", "C001");
                payment.put("operationId", date + direction);
                payment.put("operationDate", date.toString());
                payment.put("amount", direction.equals("IN") ? "100.00" : "80.00");
                payment.put("direction", direction);
                payment.put("counterpartyInn", "");
                payment.put("counterpartyName", "Test");
                payment.put("paymentPurpose", "Test");
                payment.put("balanceEod", (1000 - offset * 20) + ".00");
                payment.putObject("category")
                        .put("code", direction.equals("IN") ? "SALES" : "SUPPLIERS")
                        .put("source", "RULE")
                        .put("rulesVersion", "R1");
            }
            balances.addObject()
                    .put("companyId", "C001")
                    .put("date", date.toString())
                    .put("balanceEod", (1000 - offset * 20) + ".00")
                    .put("balanceStatus", "OBSERVED");
        }
        payload.putObject("report")
                .put("status", "PREPARED")
                .put("issueCount", 0)
                .putArray("issues");
        var staged =
                send(
                        "POST",
                        base + "/imports?completeness=CONFIRMED_COMPLETE&declaredFromDate="
                                + cutoff.minusDays(6) + "&declaredToDate=" + cutoff,
                        owner,
                        payload.toString(),
                        "Idempotency-Key",
                        "real-import-001");
        assertThat(staged.status()).as(staged.raw()).isEqualTo(201);
        var finalized =
                send(
                        "POST",
                        base + "/imports/" + staged.body().path("id").asText() + "/finalize",
                        owner,
                        null,
                        "If-Match",
                        "\"" + staged.body().path("version").asLong() + "\"");
        assertThat(finalized.status()).as(finalized.raw()).isEqualTo(200);

        String content =
                "{\"title\":\"Test"
                    + " plan\",\"direction\":\"OUT\",\"amount\":\"100.00\",\"dueDate\":\"%s\",\"categoryCode\":\"SUPPLIERS\",\"relation\":\"ADDITIONAL\",\"source\":\"MANUAL\"}";
        var plan =
                send(
                        "POST",
                        base + "/expected-payments",
                        owner,
                        content.formatted(cutoff.plusDays(5)));
        assertThat(plan.status()).as(plan.raw()).isEqualTo(201);
        assertThat(plan.body().path("version").asLong()).isZero();
        assertThat(
                        send("POST", base + "/expected-payments", owner, content.formatted(cutoff))
                                .status())
                .isEqualTo(201);
        var created =
                send(
                        "POST",
                        base + "/forecast-jobs",
                        owner,
                        "{\"asOfDate\":\"" + cutoff + "\",\"scope\":\"WITH_KNOWN_PLANS\"}",
                        "Idempotency-Key",
                        "real-forecast-001");
        assertThat(created.status()).as(created.raw()).isEqualTo(202);
        UUID jobId = UUID.fromString(created.body().path("id").asText());
        assertThat(worker.runOnce()).isTrue();
        var job =
                send(
                        "GET",
                        "/api/v1/orchestrator/companies/" + company + "/jobs/" + jobId,
                        owner,
                        null);
        assertThat(job.body().path("status").asText()).as(job.raw()).isEqualTo("SUCCEEDED");
        var result =
                send(
                        "GET",
                        base + "/forecasts/" + job.body().path("resultId").asText(),
                        owner,
                        null);
        assertThat(result.status()).as(result.raw()).isEqualTo(200);
        assertThat(result.body().path("days").size()).isEqualTo(30);
        assertThat(result.body().path("days").get(4).path("balance").asText()).isEqualTo("1000.00");
        assertThat(result.body().path("days").get(29).path("balance").asText())
                .isEqualTo("1500.00");
        assertThat(result.body().path("riskEstimate").path("probability").isNull()).isTrue();
        var scope = access.requireRead(owner, company);
        var stored =
                snapshots
                        .find(scope, UUID.fromString(created.body().path("snapshotId").asText()))
                        .orElseThrow();
        var input = json.readTree(stored.payload());
        assertThat(java.time.Instant.parse(input.path("knowledgeCutoff").asText()))
                .isAfter(
                        cutoff.plusDays(1)
                                .atStartOfDay(java.time.ZoneId.of("Europe/Moscow"))
                                .toInstant());
        // Today's import is usable now, but must remain invisible in operational replay of
        // yesterday.
        assertThatThrownBy(
                        () ->
                                snapshotService.buildHistorical(
                                        scope,
                                        account,
                                        cutoff,
                                        "HISTORY_ONLY",
                                        input.path("inputDataVersion").asLong(),
                                        "Europe/Moscow"))
                .isInstanceOf(SnapshotException.class);
    }
}
