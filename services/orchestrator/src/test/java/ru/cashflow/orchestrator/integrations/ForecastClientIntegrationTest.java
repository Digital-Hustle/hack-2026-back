package ru.cashflow.orchestrator.integrations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.forecast.application.ForecastWorker;
import ru.cashflow.orchestrator.features.forecast.gateway.ForecastClient;
import ru.cashflow.orchestrator.features.jobs.repository.JobRepository;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.AnchorSource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;
import ru.cashflow.orchestrator.support.FakeForecastServer;
import ru.cashflow.orchestrator.support.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(ForecastClientIntegrationTest.ClockConfig.class)
@TestPropertySource(properties = "orchestrator.jobs.worker-enabled=false")
class ForecastClientIntegrationTest extends AbstractIntegrationTest {

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
        registry.add("orchestrator.forecast.request-timeout", () -> "2s");
    }

    @AfterAll
    static void stop() {
        FORECAST.close();
    }

    @Autowired
    ForecastWorker worker;
    @Autowired
    ForecastClient client;
    @Autowired
    JobRepository jobs;
    @Autowired
    LedgerRepository ledger;
    @Autowired
    CompanyAccess access;
    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void reset() {
        FORECAST.reset();
    }

    @Test
    void callHappensAfterCommitWithContractHeadersAndWithoutUserCredentials() throws Exception {
        String jobId = createJob("client-key-0001");
        FORECAST.respond(r -> new FakeForecastServer.Reply(200, "{}", Map.of()));

        assertThat(worker.runOnce()).isTrue();

        assertThat(FORECAST.requests()).hasSize(1);
        var request = FORECAST.requests().get(0);
        var job = jobs.findById(UUID.fromString(jobId)).orElseThrow();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/internal/v1/forecast/calculate");
        assertThat(request.header("Idempotency-Key")).isEqualTo(jobId);
        assertThat(request.header("X-Job-Id")).isEqualTo(jobId);
        assertThat(request.header("X-Calculation-Id")).isEqualTo(job.resultId().toString());
        assertThat(request.header("Authorization")).isNull();
        assertThat(request.header("Cookie")).isNull();
        assertThat(request.headers().keySet()).noneMatch(h -> h.toLowerCase().startsWith("x-user"));
        var body = json.readTree(request.body());
        assertThat(body.path("snapshotId").asText()).isEqualTo(job.snapshotId().toString());
        assertThat(Fingerprint.of(body)).isEqualTo(body.path("fingerprint").asText());
        // An empty 200 body is not a forecast: the validator rejects it instead of storing anything.
        assertThat(job.status().name()).isEqualTo("FAILED");
        assertThat(job.errorJson()).contains("INVALID_FORECAST_RESULT");
        assertThat(jdbc.sql("select count(*) from forecasts").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from snapshots").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void retryableFailureSendsSameBytesAndKeysAndPermanentFailureIsNotRepeated() throws Exception {
        String jobId = createJob("client-key-0002");
        FORECAST.respond(r -> new FakeForecastServer.Reply(503, "{\"code\":\"OVERLOADED\"}",
                Map.of("Retry-After", "0")));
        worker.runOnce();
        assertThat(jobs.findById(UUID.fromString(jobId)).orElseThrow().status().name()).isEqualTo("QUEUED");
        worker.runOnce();
        assertThat(FORECAST.requests()).hasSize(2);
        assertThat(FORECAST.requests().get(1).body()).isEqualTo(FORECAST.requests().get(0).body());
        assertThat(FORECAST.requests().get(1).header("X-Calculation-Id"))
                .isEqualTo(FORECAST.requests().get(0).header("X-Calculation-Id"));

        FORECAST.respond(r -> new FakeForecastServer.Reply(422, "{\"code\":\"INSUFFICIENT_HISTORY\"}", Map.of()));
        worker.runOnce();
        var job = jobs.findById(UUID.fromString(jobId)).orElseThrow();
        assertThat(job.status().name()).isEqualTo("FAILED");
        assertThat(job.errorJson()).contains("INSUFFICIENT_HISTORY");
        int calls = FORECAST.requests().size();
        assertThat(worker.runOnce()).isFalse();
        assertThat(FORECAST.requests()).hasSize(calls);
    }

    @Test
    void unavailableForecastExhaustsAttemptsWithoutFakeResult() throws Exception {
        String jobId = createJob("client-key-0003");
        FORECAST.respond(r -> new FakeForecastServer.Reply(503, "{}", Map.of("Retry-After", "0")));
        for (int i = 0; i < 5; i++) {
            worker.runOnce();
        }
        var job = jobs.findById(UUID.fromString(jobId)).orElseThrow();
        assertThat(job.status().name()).isEqualTo("FAILED");
        assertThat(job.attempts()).isEqualTo(3);
        assertThat(FORECAST.requests()).hasSize(3);
    }

    @Test
    void clientRefusesToRunInsideTransaction() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> client.post(ForecastClient.PATH_CALCULATE, "{}",
                UUID.randomUUID(), UUID.randomUUID()))).hasMessageContaining("transaction");
        assertThat(FORECAST.requests()).isEmpty();
    }

    @Test
    void tamperedSnapshotIsNeverSent() throws Exception {
        String jobId = createJob("client-key-0004");
        var job = jobs.findById(UUID.fromString(jobId)).orElseThrow();
        jdbc.sql("alter table snapshots disable trigger snapshots_no_update").update();
        jdbc.sql("update snapshots set payload = jsonb_set(payload, '{startBalance}', '\"1.00\"') where id = :id")
                .param("id", job.snapshotId()).update();
        jdbc.sql("alter table snapshots enable trigger snapshots_no_update").update();

        worker.runOnce();
        assertThat(FORECAST.requests()).isEmpty();
        assertThat(jobs.findById(job.id()).orElseThrow().errorJson()).contains("FINGERPRINT_MISMATCH");
    }

    private String createJob(String key) throws Exception {
        CLOCK.set(Instant.parse("2026-10-17T09:00:00Z"));
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Client");
        UUID account = createAccount(owner, company, null);
        var scope = access.requireWrite(owner, company);
        Instant known = CLOCK.instant();
        for (int i = 0; i < 7; i++) {
            ledger.insertPayment(new Payment(UUID.randomUUID(), company, account, null, null, null,
                    LocalDate.parse("2026-10-18").plusDays(i), new BigDecimal("100.00"), Direction.IN, null, null, null,
                    null, "SALES", CategorySource.RULE, "R1", null, 0, known, known));
        }
        ledger.insertAnchor(scope, new BalanceAnchor(UUID.randomUUID(), account, LocalDate.parse("2026-10-24"),
                new BigDecimal("1000.00"), AnchorSource.STATEMENT, "Выписка", known, null, null), null);
        ledger.insertCompleteness(scope, new CompletenessRange(UUID.randomUUID(), account, LocalDate.parse("2026-10-18"),
                LocalDate.parse("2026-10-24"), Completeness.CONFIRMED_COMPLETE, "Выписка", known, null));
        CLOCK.set(Instant.parse("2026-10-25T09:00:00Z"));
        var response = send("POST", "/api/v1/orchestrator/companies/" + company + "/accounts/" + account
                + "/forecast-jobs", owner, "{\"asOfDate\":\"2026-10-24\",\"scope\":\"WITH_KNOWN_PLANS\"}",
                "Idempotency-Key", key);
        assertThat(response.status()).as(response.raw()).isEqualTo(202);
        return response.body().path("id").asText();
    }
}
