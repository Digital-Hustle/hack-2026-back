package ru.cashflow.orchestrator.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;
import ru.cashflow.orchestrator.support.FakeForecastServer;
import ru.cashflow.orchestrator.support.ForecastResponses;
import ru.cashflow.orchestrator.support.MutableClock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Import(ForecastResultIntegrationTest.ClockConfig.class)
class ForecastResultIntegrationTest extends AbstractIntegrationTest {

    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-25T09:00:00Z"));
    static final FakeForecastServer FORECAST;

    static {
        try {
            FORECAST = new FakeForecastServer();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration
    static class ClockConfig {
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

    @Autowired ForecastWorker worker;
    @Autowired JobRepository jobs;
    @Autowired LedgerRepository ledger;
    @Autowired CompanyAccess access;

    @BeforeEach
    void reset() {
        FORECAST.reset();
    }

    @Test
    void validResultIsStoredAtomicallyWithDaysAndJobSucceeds() throws Exception {
        UUID jobId = createJob("result-key-01");
        respondWith(rs -> {});

        worker.runOnce();

        var job = jobs.findById(jobId).orElseThrow();
        assertThat(job.status().name()).as(String.valueOf(job.errorJson())).isEqualTo("SUCCEEDED");
        assertThat(count("forecasts")).isEqualTo(1);
        assertThat(count("forecast_days")).isEqualTo(30);
        assertThat(jdbc.sql("select id from forecasts").query(UUID.class).single())
                .isEqualTo(job.resultId());
        assertThat(
                        jdbc.sql("select balance from forecast_days order by date desc limit 1")
                                .query(BigDecimal.class)
                                .single())
                .isEqualByComparingTo("1600.00");
    }

    @Test
    void resultWithForeignSnapshotOrJobIsRejected() throws Exception {
        assertRejected("result-key-02", rs -> rs.put("snapshotId", UUID.randomUUID().toString()));
        assertRejected("result-key-03", rs -> rs.put("jobId", UUID.randomUUID().toString()));
        assertRejected("result-key-04", rs -> rs.put("id", UUID.randomUUID().toString()));
        assertRejected("result-key-05", rs -> rs.put("accountId", UUID.randomUUID().toString()));
        assertRejected("result-key-06", rs -> rs.put("inputDataVersion", 999));
    }

    @Test
    void resultWith29Or31DaysIsRejected() throws Exception {
        assertRejected("result-key-07", rs -> rs.withArray("days").remove(29));
        assertRejected(
                "result-key-08",
                rs -> {
                    var days = rs.withArray("days");
                    days.add(days.get(29).deepCopy());
                });
    }

    @Test
    void arithmeticAndDomainViolationsAreRejected() throws Exception {
        assertRejected(
                "result-key-09",
                rs -> ((ObjectNode) rs.withArray("days").get(3)).put("balance", "1.00"));
        assertRejected(
                "result-key-10",
                rs -> ((ObjectNode) rs.withArray("days").get(3)).put("inflow", "999.00"));
        assertRejected(
                "result-key-11",
                rs -> ((ObjectNode) rs.withArray("days").get(0)).put("date", "2026-11-30"));
        assertRejected(
                "result-key-12",
                rs -> ((ObjectNode) rs.withArray("days").get(0)).put("residualIn", "-1.00"));
        assertRejected(
                "result-key-13",
                rs -> ((ObjectNode) rs.withArray("days").get(0)).put("pBelowZero", 0.5));
        assertRejected(
                "result-key-14",
                rs -> ((ObjectNode) rs.withArray("days").get(0)).put("balance", "1.001"));
        assertRejected("result-key-15", rs -> rs.with("riskEstimate").put("probability", 0.0));
        assertRejected("result-key-16", rs -> rs.put("quality", "EXCELLENT"));
        assertRejected("result-key-17", rs -> rs.put("expectedMinimumBalance", "0.00"));
        assertRejected(
                "result-key-18", rs -> rs.with("riskEstimate").put("probabilityCalibrated", true));
    }

    @Test
    void forecastFailureCreatesNoFakeForecast() throws Exception {
        UUID jobId = createJob("result-key-19");
        FORECAST.respond(
                r -> new FakeForecastServer.Reply(422, "{\"code\":\"UNRESOLVED_PLAN\"}", Map.of()));

        worker.runOnce();

        assertThat(jobs.findById(jobId).orElseThrow().status().name()).isEqualTo("FAILED");
        assertThat(count("forecasts")).isZero();
        assertThat(count("forecast_days")).isZero();
    }

    @Test
    void lateResponseOfExpiredWorkerDoesNotCreateSecondResultOrOverwrite() throws Exception {
        UUID jobId = createJob("result-key-20");
        Instant now = CLOCK.instant();
        var stale = jobs.claim("stale-worker", now, now.plusSeconds(10)).orElseThrow();
        CLOCK.set(now.plusSeconds(60));
        respondWith(rs -> {});
        worker.runOnce();
        assertThat(jobs.findById(jobId).orElseThrow().status().name()).isEqualTo("SUCCEEDED");
        assertThat(count("forecasts")).isEqualTo(1);

        assertThat(jobs.succeed(jobId, "stale-worker", stale.attempts(), CLOCK.instant()))
                .isFalse();
        assertThat(count("forecasts")).isEqualTo(1);
        assertThat(worker.runOnce()).isFalse();
    }

    @Test
    void responseAfterLeaseDeadlineIsDiscardedEvenWithoutAReplacementWorker() throws Exception {
        UUID id = createJob("result-expired-01");
        FORECAST.respond(
                request -> {
                    try {
                        var input = json.readTree(request.body());
                        var response =
                                ForecastResponses.valid(
                                        input,
                                        UUID.fromString(request.header("X-Calculation-Id")),
                                        UUID.fromString(request.header("X-Job-Id")),
                                        "100.00",
                                        "80.00");
                        CLOCK.set(CLOCK.instant().plusSeconds(61));
                        return new FakeForecastServer.Reply(200, response.toString(), Map.of());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
        worker.runOnce();
        assertThat(count("forecasts")).isZero();
        assertThat(jobs.findById(id).orElseThrow().status().name()).isEqualTo("RUNNING");
        assertThat(jobs.claim("recovery", CLOCK.instant(), CLOCK.instant().plusSeconds(60)))
                .isPresent();
    }

    private void assertRejected(String key, Consumer<ObjectNode> mutation) throws Exception {
        FORECAST.reset();
        UUID jobId = createJob(key);
        respondWith(mutation);
        worker.runOnce();
        var job = jobs.findById(jobId).orElseThrow();
        assertThat(job.status().name()).as(key).isEqualTo("FAILED");
        assertThat(job.errorJson()).as(key).contains("INVALID_FORECAST_RESULT");
        assertThat(
                        jdbc.sql("select count(*) from forecasts where job_id = :j")
                                .param("j", jobId)
                                .query(Long.class)
                                .single())
                .as(key)
                .isZero();
    }

    private void respondWith(Consumer<ObjectNode> mutation) {
        FORECAST.respond(
                r -> {
                    try {
                        var request = json.readTree(r.body());
                        var rs =
                                ForecastResponses.valid(
                                        request,
                                        UUID.fromString(r.header("X-Calculation-Id")),
                                        UUID.fromString(r.header("X-Job-Id")),
                                        "100.00",
                                        "80.00");
                        mutation.accept(rs);
                        return new FakeForecastServer.Reply(
                                200, json.writeValueAsString(rs), Map.of());
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
    }

    private UUID createJob(String key) throws Exception {
        CLOCK.set(Instant.parse("2026-10-17T09:00:00Z"));
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Results");
        UUID account = createAccount(owner, company, null);
        var scope = access.requireWrite(owner, company);
        Instant known = CLOCK.instant();
        for (int i = 0; i < 7; i++) {
            LocalDate date = LocalDate.parse("2026-10-18").plusDays(i);
            ledger.insertPayment(
                    new Payment(
                            UUID.randomUUID(),
                            company,
                            account,
                            null,
                            null,
                            null,
                            date,
                            new BigDecimal("100.00"),
                            Direction.IN,
                            null,
                            null,
                            null,
                            null,
                            "SALES",
                            CategorySource.RULE,
                            "R1",
                            null,
                            0,
                            known,
                            known));
            ledger.insertPayment(
                    new Payment(
                            UUID.randomUUID(),
                            company,
                            account,
                            null,
                            null,
                            null,
                            date,
                            new BigDecimal("80.00"),
                            Direction.OUT,
                            null,
                            null,
                            null,
                            null,
                            "SUPPLIERS",
                            CategorySource.RULE,
                            "R1",
                            null,
                            0,
                            known,
                            known));
        }
        ledger.insertAnchor(
                scope,
                new BalanceAnchor(
                        UUID.randomUUID(),
                        account,
                        LocalDate.parse("2026-10-24"),
                        new BigDecimal("1000.00"),
                        AnchorSource.STATEMENT,
                        "Выписка",
                        known,
                        null,
                        null),
                null);
        ledger.insertCompleteness(
                scope,
                new CompletenessRange(
                        UUID.randomUUID(),
                        account,
                        LocalDate.parse("2026-10-18"),
                        LocalDate.parse("2026-10-24"),
                        Completeness.CONFIRMED_COMPLETE,
                        "Выписка",
                        known,
                        null));
        CLOCK.set(Instant.parse("2026-10-25T09:00:00Z"));
        var response =
                send(
                        "POST",
                        "/api/v1/orchestrator/companies/"
                                + company
                                + "/accounts/"
                                + account
                                + "/forecast-jobs",
                        owner,
                        "{\"asOfDate\":\"2026-10-24\",\"scope\":\"WITH_KNOWN_PLANS\"}",
                        "Idempotency-Key",
                        key);
        assertThat(response.status()).as(response.raw()).isEqualTo(202);
        return UUID.fromString(response.body().path("id").asText());
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }
}
