package ru.cashflow.orchestrator.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.forecast.application.ForecastWorker;
import ru.cashflow.orchestrator.features.jobs.repository.JobRepository;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.AnchorSource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;
import ru.cashflow.orchestrator.support.FakeForecastServer;
import ru.cashflow.orchestrator.support.MutableClock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Import(BacktestJobIntegrationTest.TimeConfig.class)
class BacktestJobIntegrationTest extends AbstractIntegrationTest {
    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-25T09:00:00Z"));
    static final FakeForecastServer FORECAST;

    static {
        try {
            FORECAST = new FakeForecastServer();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void forecast(DynamicPropertyRegistry registry) {
        registry.add("orchestrator.forecast.base-url", FORECAST::url);
    }

    @AfterAll
    static void stop() {
        FORECAST.close();
    }

    @Autowired LedgerRepository ledger;
    @Autowired CompanyAccess access;
    @Autowired ForecastWorker worker;
    @Autowired JobRepository jobs;

    @Test
    void backtestIsIdempotentValidatedStoredAndReadableByViewer() throws Exception {
        FORECAST.reset();
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Backtest job");
        UUID account = createAccount(owner, company, null);
        var scope = access.requireWrite(owner, company);
        LocalDate origin = LocalDate.parse("2026-09-01");
        for (int offset = -6; offset <= 30; offset++) {
            LocalDate date = origin.plusDays(offset);
            Instant known = Instant.parse(date + "T18:00:00Z");
            ledger.insertAnchor(
                    scope,
                    new BalanceAnchor(
                            UUID.randomUUID(),
                            account,
                            date,
                            new BigDecimal("100000.00"),
                            AnchorSource.STATEMENT,
                            "Test",
                            known,
                            null,
                            null),
                    null);
            ledger.insertCompleteness(
                    scope,
                    new CompletenessRange(
                            UUID.randomUUID(),
                            account,
                            date,
                            date,
                            Completeness.CONFIRMED_COMPLETE,
                            "Test",
                            known,
                            null));
        }
        String base = "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;
        String body =
                "{\"asOfDate\":\"2026-10-01\",\"firstOrigin\":\"2026-09-01\",\"lastOrigin\":\"2026-09-01\",\"stepDays\":7,\"scope\":\"HISTORY_ONLY\",\"windowWeeks\":12,\"evaluateRisk\":false}";
        var created =
                send(
                        "POST",
                        base + "/backtest-jobs",
                        owner,
                        body,
                        "Idempotency-Key",
                        "backtest-job-001");
        assertThat(created.status()).as(created.raw()).isEqualTo(202);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            java.util.concurrent.Callable<Integer> create =
                    () ->
                            send(
                                            "POST",
                                            base + "/backtest-jobs",
                                            owner,
                                            body,
                                            "Idempotency-Key",
                                            "backtest-job-001")
                                    .status();
            for (var response : pool.invokeAll(java.util.List.of(create, create, create))) {
                assertThat(response.get()).isEqualTo(202);
            }
        }
        assertThat(jdbc.sql("select count(*) from jobs").query(Long.class).single()).isEqualTo(1);
        assertThat(
                        send(
                                        "POST",
                                        base + "/backtest-jobs",
                                        owner,
                                        body.replace("\"stepDays\":7", "\"stepDays\":14"),
                                        "Idempotency-Key",
                                        "backtest-job-001")
                                .status())
                .isEqualTo(409);
        assertThat(
                        send(
                                        "POST",
                                        base + "/backtest-jobs",
                                        owner,
                                        body,
                                        "Idempotency-Key",
                                        "backtest-job-001")
                                .body()
                                .path("id"))
                .isEqualTo(created.body().path("id"));
        UUID jobId = UUID.fromString(created.body().path("id").asText());
        FORECAST.respond(
                request -> {
                    try {
                        var input = json.readTree(request.body());
                        var response = json.createObjectNode();
                        for (String field :
                                java.util.List.of(
                                        "snapshotId",
                                        "companyId",
                                        "accountId",
                                        "inputDataVersion")) {
                            response.set(field, input.get(field));
                        }
                        response.put("id", request.header("X-Calculation-Id"));
                        response.put("jobId", request.header("X-Job-Id"));
                        response.put("algorithm", "WEEKDAY_MEAN_V1");
                        response.put("algorithmVersion", "WEEKDAY_MEAN_V1");
                        for (String field :
                                java.util.List.of(
                                        "scope",
                                        "windowWeeks",
                                        "firstOrigin",
                                        "lastOrigin",
                                        "stepDays")) {
                            response.set(field, input.path("request").get(field));
                        }
                        response.put("status", "COMPLETE");
                        response.put("evaluatedOrigins", 1);
                        response.put("skippedOrigins", 0);
                        response.put("evaluatedDays", 30);
                        response.put("maeBalance", "0.00");
                        response.put("rmseBalance", "0.00");
                        var horizons = response.putArray("horizons");
                        for (int h = 1; h <= 30; h++) {
                            var metric = horizons.addObject();
                            metric.put("horizonDay", h);
                            metric.put("maeBalance", "0.00");
                            metric.put("rmseBalance", "0.00");
                            metric.put("evaluatedCount", 1);
                        }
                        var gap = response.putObject("gapMetrics");
                        gap.put("truePositive", 0);
                        gap.put("falsePositive", 0);
                        gap.put("falseNegative", 0);
                        for (String key :
                                java.util.List.of(
                                        "precision",
                                        "recall",
                                        "medianLeadDays",
                                        "dateMaeDays",
                                        "shortfallMae")) {
                            gap.putNull(key);
                        }
                        var risk = response.putObject("riskMetrics");
                        risk.putNull("brierScore");
                        risk.put("evaluatedCount", 0);
                        risk.putNull("interval80Coverage");
                        var bins = risk.putArray("calibrationBins");
                        for (int b = 0; b < 5; b++) {
                            var bin = bins.addObject();
                            bin.put("fromProbability", b / 5.0);
                            bin.put("toProbability", (b + 1) / 5.0);
                            bin.put("count", 0);
                            bin.putNull("meanProbability");
                            bin.putNull("observedRate");
                        }
                        var result = response.putArray("origins").addObject();
                        result.put("asOfDate", "2026-09-01");
                        result.put("status", "EVALUATED");
                        result.putNull("reason");
                        result.put("maeBalance", "0.00");
                        result.put("evaluatedDays", 30);
                        response.putArray("warnings");
                        response.put("createdAt", CLOCK.instant().toString());
                        response.put("staleness", "CURRENT");
                        return new FakeForecastServer.Reply(200, response.toString(), Map.of());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
        worker.runOnce();
        var job = jobs.findById(jobId).orElseThrow();
        assertThat(job.status().name()).as(job.errorJson()).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("select count(*) from backtest_origins").query(Long.class).single())
                .isEqualTo(1);
        UUID viewer = UUID.randomUUID();
        addMember(owner, company, viewer, "VIEWER");
        var result = send("GET", base + "/backtests/" + job.resultId(), viewer, null);
        assertThat(result.status()).as(result.raw()).isEqualTo(200);
        assertThat(result.body().path("maeBalance").asText()).isEqualTo("0.00");
        var origins = send("GET", base + "/backtests/" + job.resultId() + "/origins", viewer, null);
        assertThat(origins.status()).as(origins.raw()).isEqualTo(200);
        assertThat(origins.body().path("items").size()).isEqualTo(1);
        assertThat(
                        send(
                                        "POST",
                                        base + "/backtest-jobs",
                                        viewer,
                                        body,
                                        "Idempotency-Key",
                                        "backtest-job-002")
                                .status())
                .isEqualTo(403);
        assertThat(
                        send("GET", base + "/backtests/" + job.resultId(), UUID.randomUUID(), null)
                                .status())
                .isEqualTo(404);
        jdbc.sql("delete from memberships where company_id=:company and user_id=:user")
                .param("company", company)
                .param("user", viewer)
                .update();
        assertThat(send("GET", base + "/backtests/" + job.resultId(), viewer, null).status())
                .isEqualTo(404);
        var failed =
                send(
                        "POST",
                        base + "/backtest-jobs",
                        owner,
                        body,
                        "Idempotency-Key",
                        "backtest-bad-response");
        UUID failedId = UUID.fromString(failed.body().path("id").asText());
        FORECAST.respond(request -> new FakeForecastServer.Reply(200, "{}", Map.of()));
        worker.runOnce();
        assertThat(jobs.findById(failedId).orElseThrow().status().name()).isEqualTo("FAILED");
        assertThat(jobs.findById(failedId).orElseThrow().errorJson())
                .contains("INVALID_BACKTEST_RESULT");
        assertThat(
                        jdbc.sql("select count(*) from backtests where job_id=:job")
                                .param("job", failedId)
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(worker.runOnce()).isFalse();
    }
}
