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
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
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

@Import(RecurringDetectionJobIntegrationTest.TimeConfig.class)
class RecurringDetectionJobIntegrationTest extends AbstractIntegrationTest {
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
    void detectionJobCallsForecastMergesCandidatesAndPreservesUserDecision() throws Exception {
        FORECAST.reset();
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Recurring job");
        UUID account = createAccount(owner, company, null);
        var scope = access.requireWrite(owner, company);
        LocalDate cutoff = LocalDate.of(2026, 10, 24);
        for (int index = 0; index < 4; index++) {
            LocalDate date = cutoff.minusDays((long) index * 7);
            Instant known = date.atTime(18, 0).toInstant(java.time.ZoneOffset.UTC);
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
                            Direction.OUT,
                            "supplier-a",
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
            ledger.insertAnchor(
                    scope,
                    new BalanceAnchor(
                            UUID.randomUUID(),
                            account,
                            date,
                            new BigDecimal("1000.00"),
                            AnchorSource.STATEMENT,
                            "test",
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
                            "test",
                            known,
                            null));
        }
        String base = "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;
        var created =
                send(
                        "POST",
                        base + "/recurring-detection-jobs",
                        owner,
                        "{\"asOfDate\":\"2026-10-24\"}",
                        "Idempotency-Key",
                        "recurring-job-001");
        assertThat(created.status()).as(created.raw()).isEqualTo(202);
        assertThat(
                        send(
                                        "POST",
                                        base + "/recurring-detection-jobs",
                                        owner,
                                        "{\"asOfDate\":\"2026-10-24\"}",
                                        "Idempotency-Key",
                                        "recurring-job-001")
                                .body()
                                .path("id"))
                .isEqualTo(created.body().path("id"));
        UUID jobId = UUID.fromString(created.body().path("id").asText());
        FORECAST.respond(
                request ->
                        new FakeForecastServer.Reply(
                                200,
                                "{\"detectorVersion\":\"RECURRENCE_V1\",\"patterns\":["
                                    + "{\"id\":\"66666666-6666-4666-8666-666666666666\",\"accountId\":\""
                                        + account
                                        + "\",\"direction\":\"OUT\",\"categoryCode\":\"SUPPLIERS\",\"counterpartyKey\":\"supplier-a\","
                                        + "\"status\":\"DETECTED\",\"schedule\":{\"frequency\":\"WEEKLY\",\"weekday\":6,\"dayOfMonth\":null,\"businessDayRule\":\"NONE\"},"
                                        + "\"amount\":\"100.00\",\"observations\":4,\"firstSeen\":\"2026-10-03\",\"lastSeen\":\"2026-10-24\",\"inputDataVersion\":1,\"detectorVersion\":\"RECURRENCE_V1\",\"version\":0}]}"
                                        + " ",
                                Map.of()));
        assertThat(worker.runOnce()).isTrue();
        assertThat(jobs.findById(jobId).orElseThrow().status().name()).isEqualTo("SUCCEEDED");
        var patterns = send("GET", base + "/recurring-patterns", owner, null).body().path("items");
        assertThat(patterns).hasSize(1);
        assertThat(patterns.get(0).path("status").asText()).isEqualTo("DETECTED");
        var patternId = patterns.get(0).path("id").asText();
        var decision =
                send(
                        "PUT",
                        base + "/recurring-patterns/" + patternId + "/status",
                        owner,
                        "{\"status\":\"EXCLUDED\"}",
                        "If-Match",
                        "\"0\"");
        assertThat(decision.status()).as(decision.raw()).isEqualTo(200);
        FORECAST.reset();
        var second =
                send(
                        "POST",
                        base + "/recurring-detection-jobs",
                        owner,
                        "{\"asOfDate\":\"2026-10-24\"}",
                        "Idempotency-Key",
                        "recurring-job-002");
        assertThat(second.status()).isEqualTo(202);
        assertThat(worker.runOnce()).isTrue();
        assertThat(
                        send("GET", base + "/recurring-patterns", owner, null)
                                .body()
                                .path("items")
                                .get(0)
                                .path("status")
                                .asText())
                .isEqualTo("EXCLUDED");
    }

    @Test
    void viewerCannotStartDetectionAndInvalidCandidateFailsWithoutPersistence() throws Exception {
        FORECAST.reset();
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Detection access");
        UUID account = createAccount(owner, company, null);
        UUID viewer = UUID.randomUUID();
        addMember(owner, company, viewer, "VIEWER");
        String path =
                "/api/v1/orchestrator/companies/"
                        + company
                        + "/accounts/"
                        + account
                        + "/recurring-detection-jobs";
        assertThat(
                        send(
                                        "POST",
                                        path,
                                        viewer,
                                        "{\"asOfDate\":\"2026-10-24\"}",
                                        "Idempotency-Key",
                                        "recurring-viewer-1")
                                .status())
                .isEqualTo(403);
        var created =
                send(
                        "POST",
                        path,
                        owner,
                        "{\"asOfDate\":\"2026-10-24\"}",
                        "Idempotency-Key",
                        "recurring-bad-1");
        assertThat(created.status()).isEqualTo(202);
        UUID jobId = UUID.fromString(created.body().path("id").asText());
        FORECAST.respond(
                request ->
                        new FakeForecastServer.Reply(
                                200,
                                "{\"detectorVersion\":\"RECURRENCE_V1\",\"patterns\":["
                                        + "{\"accountId\":\"77777777-7777-4777-8777-777777777777\","
                                        + "\"status\":\"DETECTED\",\"amount\":\"100.00\",\"observations\":3}]}" ,
                                Map.of()));
        assertThat(worker.runOnce()).isTrue();
        assertThat(jobs.findById(jobId).orElseThrow().status().name()).isEqualTo("FAILED");
        String patternsPath = path.substring(0, path.lastIndexOf("/recurring-detection-jobs"))
                + "/recurring-patterns";
        assertThat(send("GET", patternsPath, owner, null).body().path("items")).isEmpty();
    }
}
