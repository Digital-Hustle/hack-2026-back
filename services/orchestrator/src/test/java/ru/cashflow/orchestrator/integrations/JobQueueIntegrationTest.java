package ru.cashflow.orchestrator.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
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
import ru.cashflow.orchestrator.support.MutableClock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

@Import(JobQueueIntegrationTest.ClockConfig.class)
class JobQueueIntegrationTest extends AbstractIntegrationTest {

    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-25T09:00:00Z"));

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired JobRepository jobs;
    @Autowired LedgerRepository ledger;
    @Autowired CompanyAccess access;

    @Test
    void sameIdempotencyKeyCreatesOneJobAndDifferentBodyConflicts() throws Exception {
        var ctx = ctx();
        var first = create(ctx, "forecast-key-1", "2026-10-24", "WITH_KNOWN_PLANS");
        var second = create(ctx, "forecast-key-1", "2026-10-24", "WITH_KNOWN_PLANS");

        assertThat(first.status()).isEqualTo(202);
        assertThat(first.body().path("status").asText()).isEqualTo("QUEUED");
        assertThat(first.body().path("origin").asText()).isEqualTo("MANUAL");
        assertThat(first.body().path("resultId").isNull()).isTrue();
        assertThat(second.body().path("id").asText()).isEqualTo(first.body().path("id").asText());
        assertThat(create(ctx, "forecast-key-1", "2026-10-24", "HISTORY_ONLY").status())
                .isEqualTo(409);
        assertThat(count("jobs")).isEqualTo(1);
        assertThat(count("snapshots")).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOneJob() throws Exception {
        var ctx = ctx();
        try (var pool = Executors.newFixedThreadPool(4)) {
            Callable<Integer> call =
                    () ->
                            create(ctx, "forecast-key-race", "2026-10-24", "WITH_KNOWN_PLANS")
                                    .status();
            var results = pool.invokeAll(java.util.List.of(call, call, call, call));
            for (var r : results) {
                assertThat(r.get()).isIn(202, 409);
            }
        }
        assertThat(count("jobs")).isEqualTo(1);
        assertThat(count("snapshots")).isEqualTo(1);
        var ids = new java.util.HashSet<String>();
        for (int i = 0; i < 3; i++) {
            ids.add(
                    create(ctx, "forecast-key-race", "2026-10-24", "WITH_KNOWN_PLANS")
                            .body()
                            .path("id")
                            .asText());
        }
        assertThat(ids).hasSize(1);
    }

    @Test
    void claimIsExclusiveLeaseIsFencedAndExpiredLeaseIsRecovered() throws Exception {
        var ctx = ctx();
        create(ctx, "forecast-key-2", "2026-10-24", "WITH_KNOWN_PLANS");
        Instant now = CLOCK.instant();

        var claimed = jobs.claim("worker-a", now, now.plusSeconds(60)).orElseThrow();
        assertThat(claimed.status().name()).isEqualTo("RUNNING");
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(jobs.claim("worker-b", now, now.plusSeconds(60))).isEmpty();

        var recovered =
                jobs.claim("worker-b", now.plusSeconds(120), now.plusSeconds(180)).orElseThrow();
        assertThat(recovered.id()).isEqualTo(claimed.id());
        assertThat(recovered.attempts()).isEqualTo(2);
        assertThat(recovered.resultId()).isEqualTo(claimed.resultId());

        assertThat(jobs.succeed(claimed.id(), "worker-a", 1, now.plusSeconds(130))).isFalse();
        assertThat(jobs.succeed(claimed.id(), "worker-b", 2, now.plusSeconds(130))).isTrue();
        assertThat(jobs.succeed(claimed.id(), "worker-b", 2, now.plusSeconds(131))).isFalse();
    }

    @Test
    void retryBackoffAndAttemptLimit() throws Exception {
        var ctx = ctx();
        create(ctx, "forecast-key-3", "2026-10-24", "WITH_KNOWN_PLANS");
        Instant t = CLOCK.instant();

        var a1 = jobs.claim("w", t, t.plusSeconds(30)).orElseThrow();
        assertThat(
                        jobs.retryLater(
                                a1.id(),
                                "w",
                                1,
                                "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}",
                                t.plusSeconds(10)))
                .isTrue();
        assertThat(jobs.claim("w", t.plusSeconds(5), t.plusSeconds(60))).isEmpty();
        var a2 = jobs.claim("w", t.plusSeconds(10), t.plusSeconds(40)).orElseThrow();
        assertThat(a2.attempts()).isEqualTo(2);
        jobs.retryLater(a2.id(), "w", 2, "{}", t.plusSeconds(10));
        var a3 = jobs.claim("w", t.plusSeconds(10), t.plusSeconds(40)).orElseThrow();
        assertThat(a3.attempts()).isEqualTo(3);

        assertThat(jobs.claim("w", t.plusSeconds(100), t.plusSeconds(160))).isEmpty();
        assertThat(jobs.failExhausted(t.plusSeconds(100))).isEqualTo(1);
        assertThat(jobs.findById(a3.id()).orElseThrow().status().name()).isEqualTo("FAILED");
    }

    @Test
    void expiredAttemptCannotFinishFailExtendOrRequeueBeforeAnotherWorkerClaimsIt()
            throws Exception {
        var ctx = ctx();
        create(ctx, "forecast-expired-01", "2026-10-24", "WITH_KNOWN_PLANS");
        Instant now = CLOCK.instant();
        var job = jobs.claim("expired", now, now.plusSeconds(10)).orElseThrow();
        CLOCK.set(now.plusSeconds(10));
        assertThat(jobs.succeed(job.id(), "expired", 1, CLOCK.instant())).isFalse();
        assertThat(jobs.fail(job.id(), "expired", 1, "{}", CLOCK.instant())).isFalse();
        assertThat(jobs.extend(job.id(), "expired", 1, now.plusSeconds(100))).isFalse();
        assertThat(jobs.retryLater(job.id(), "expired", 1, "{}", now.plusSeconds(20))).isFalse();
        assertThat(jobs.claim("replacement", CLOCK.instant(), now.plusSeconds(70))).isPresent();
    }

    @Test
    void accessRulesAndCancel() throws Exception {
        var ctx = ctx();
        UUID viewer = UUID.randomUUID();
        addMember(ctx.owner(), ctx.company(), viewer, "VIEWER");
        assertThat(
                        send(
                                        "POST",
                                        path(ctx),
                                        viewer,
                                        body("2026-10-24", "WITH_KNOWN_PLANS"),
                                        "Idempotency-Key",
                                        "forecast-key-v")
                                .status())
                .isEqualTo(403);
        assertThat(
                        send(
                                        "POST",
                                        path(ctx),
                                        UUID.randomUUID(),
                                        body("2026-10-24", "WITH_KNOWN_PLANS"),
                                        "Idempotency-Key",
                                        "forecast-key-x")
                                .status())
                .isEqualTo(404);
        assertThat(create(ctx, "forecast-key-4", "2026-10-25", "WITH_KNOWN_PLANS").status())
                .isEqualTo(422);
        assertThat(count("jobs")).isZero();

        String id =
                create(ctx, "forecast-key-5", "2026-10-24", "WITH_KNOWN_PLANS")
                        .body()
                        .path("id")
                        .asText();
        String jobPath = "/api/v1/orchestrator/companies/" + ctx.company() + "/jobs/" + id;
        assertThat(send("GET", jobPath, viewer, null).status()).isEqualTo(200);
        assertThat(
                        send("POST", jobPath + "/cancel", ctx.owner(), null)
                                .body()
                                .path("status")
                                .asText())
                .isEqualTo("CANCELLED");
        assertThat(jobs.claim("w", CLOCK.instant(), CLOCK.instant().plusSeconds(30))).isEmpty();
    }

    private Ctx ctx() throws Exception {
        CLOCK.set(Instant.parse("2026-10-17T09:00:00Z"));
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Jobs");
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
        return new Ctx(owner, company, account);
    }

    private Response create(Ctx ctx, String key, String asOf, String scope) throws Exception {
        return send("POST", path(ctx), ctx.owner(), body(asOf, scope), "Idempotency-Key", key);
    }

    private static String path(Ctx ctx) {
        return "/api/v1/orchestrator/companies/"
                + ctx.company()
                + "/accounts/"
                + ctx.account()
                + "/forecast-jobs";
    }

    private static String body(String asOf, String scope) {
        return "{\"asOfDate\":\"" + asOf + "\",\"scope\":\"" + scope + "\"}";
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    private record Ctx(UUID owner, UUID company, UUID account) {}
}
