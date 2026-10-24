package ru.cashflow.orchestrator.integrations;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.AnchorSource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.features.recurring.application.RecurringService;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.BusinessDayRule;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.DetectedCandidate;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Frequency;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.PatternStatus;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Schedule;
import ru.cashflow.orchestrator.features.settings.model.ForecastSettings;
import ru.cashflow.orchestrator.features.snapshot.application.BacktestSnapshotService;
import ru.cashflow.orchestrator.features.snapshot.command.BacktestSnapshotException;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;
import ru.cashflow.orchestrator.features.snapshot.command.SnapshotException;
import ru.cashflow.orchestrator.features.snapshot.model.BacktestOrigin.Status;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.BacktestRqW;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;
import ru.cashflow.orchestrator.support.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@Import(BacktestSnapshotIntegrationTest.ClockConfig.class)
class BacktestSnapshotIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate ORIGIN = LocalDate.parse("2026-09-01");
    private static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(NOW);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    BacktestSnapshotService service;
    @Autowired
    CompanyAccess access;
    @Autowired
    LedgerRepository ledger;
    @Autowired
    RecurringService recurring;
    @Autowired
    SnapshotRepository snapshots;
    @MockitoSpyBean
    CompanyRepository companies;

    @Test
    void matchesHandoffBacktestShapeAndKeepsScoringSeparateFromTraining() throws Exception {
        var ctx = seed(30);
        var built = build(ctx, "2026-09-01", "2026-09-01", 7, "2026-10-01", "HISTORY_ONLY");
        var again = build(ctx, "2026-09-01", "2026-09-01", 7, "2026-10-01", "HISTORY_ONLY");
        JsonNode payload = json.valueToTree(built.snapshot());
        JsonNode fixture;
        try (InputStream in = getClass().getResourceAsStream("/forecast-examples/backtest.json")) {
            fixture = json.readTree(in);
        }
        assertThat(fields(payload)).containsExactlyInAnyOrderElementsOf(fields(fixture));
        assertThat(fields(payload.path("request"))).containsExactlyInAnyOrderElementsOf(fields(fixture.path("request")));
        assertThat(fields(payload.path("originSnapshots").get(0)))
                .containsExactlyInAnyOrderElementsOf(fields(fixture.path("originSnapshots").get(0)));
        assertThat(fields(payload.path("actualDays").get(0)))
                .containsExactlyInAnyOrderElementsOf(fields(fixture.path("actualDays").get(0)));
        var input = built.snapshot().originSnapshots().get(0);
        assertThat(input.historyFrom()).isEqualTo("2026-08-26");
        assertThat(input.startBalance()).isEqualTo("100000.00");
        assertThat(input.dailyHistory()).hasSize(7);
        assertThat(input.payments()).hasSize(14).allMatch(p -> p.date().compareTo("2026-09-01") <= 0);
        assertThat(input.knownPlans()).isEmpty();
        assertThat(input.calibrationOrigins()).isEmpty();
        assertThat(Fingerprint.of(json.valueToTree(input))).isEqualTo(input.fingerprint());
        assertThat(built.snapshot().actualDays()).hasSize(30).allSatisfy(day -> {
            assertThat(day.status()).isEqualTo("ZERO_CONFIRMED");
            assertThat(day.inflow()).isEqualTo("0.00");
            assertThat(day.balanceEod()).isEqualTo("100000.00");
        });
        assertThat(built.snapshot().snapshotId()).isNotEqualTo(again.snapshot().snapshotId());
        assertThat(input.snapshotId()).isNotEqualTo(again.snapshot().originSnapshots().get(0).snapshotId());
        assertThat(built.fingerprint()).isEqualTo(again.fingerprint()).isEqualTo(Fingerprint.of(payload));
        JsonNode provenance = json.readTree(built.provenance());
        assertThat(provenance.path("replayMode").asText()).isEqualTo("HISTORICAL_KNOWN_AT");
        assertThat(provenance.path("scoringKnownBefore").asText()).isEqualTo("2026-10-01T21:00:00Z");
        assertThat(provenance.path("origins").get(0).path("inputProvenance").path("knownBefore").asText())
                .isEqualTo("2026-09-01T21:00:00Z");
    }

    @Test
    void followsRequestedGridExcludesImmatureOriginsAndDeduplicatesScoringDates() throws Exception {
        var ctx = seed(37);
        var built = build(ctx, "2026-09-01", "2026-09-16", 7, "2026-10-08", "HISTORY_ONLY");
        assertThat(built.snapshot().originSnapshots()).extracting(s -> s.asOfDate())
                .containsExactly("2026-09-01", "2026-09-08");
        assertThat(built.origins()).extracting(o -> o.asOfDate().toString())
                .containsExactly("2026-09-01", "2026-09-08", "2026-09-15");
        assertThat(built.origins().get(2).status()).isEqualTo(Status.SKIPPED);
        assertThat(built.origins().get(2).reason()).isEqualTo("IMMATURE_ORIGIN");
        assertThat(built.snapshot().actualDays()).hasSize(37);
        assertThat(built.snapshot().actualDays()).extracting(d -> d.date()).doesNotHaveDuplicates().isSorted();
        assertThat(built.snapshot().actualDays().get(0).date()).isEqualTo("2026-09-02");
        assertThat(built.snapshot().actualDays().get(36).date()).isEqualTo("2026-10-08");
    }

    @Test
    void restoresCategoriesPlansRemainingAndConfirmedPatternsAtOriginWithoutFutureFacts() throws Exception {
        var ctx = seed(30);
        CLOCK.set(Instant.parse("2026-09-01T09:00:00Z"));
        String plan = plan(ctx, "Известный счёт");
        assertThat(send("POST", base(ctx) + "/expected-payments/" + plan + "/allocations", ctx.owner(),
                "{\"paymentId\":\"" + ctx.trainingIn().get(0) + "\",\"amount\":\"400.00\"}").status()).isEqualTo(201);
        var scope = access.requireWrite(ctx.owner(), ctx.company());
        recurring.mergeDetection(scope, ctx.account(), "DET1", 0, List.of(candidate("early"), candidate("late")));
        var early = recurring.list(ctx.owner(), ctx.company(), ctx.account()).stream()
                .filter(p -> p.counterpartyKey().equals("early")).findFirst().orElseThrow();
        var late = recurring.list(ctx.owner(), ctx.company(), ctx.account()).stream()
                .filter(p -> p.counterpartyKey().equals("late")).findFirst().orElseThrow();
        recurring.decide(ctx.owner(), ctx.company(), ctx.account(), early.id(), 0, PatternStatus.CONFIRMED);

        CLOCK.set(Instant.parse("2026-09-02T09:00:00Z"));
        plan(ctx, "Поздний счёт");
        assertThat(send("POST", base(ctx) + "/expected-payments/" + plan + "/allocations", ctx.owner(),
                "{\"paymentId\":\"" + ctx.trainingIn().get(1) + "\",\"amount\":\"600.00\"}").status()).isEqualTo(201);
        assertThat(send("PUT", base(ctx) + "/payments/" + ctx.trainingIn().get(0) + "/category", ctx.owner(),
                "{\"categoryCode\":\"REFUND\"}", "If-Match", "\"0\"").status()).isEqualTo(200);
        recurring.decide(ctx.owner(), ctx.company(), ctx.account(), early.id(), 1, PatternStatus.EXCLUDED);
        recurring.decide(ctx.owner(), ctx.company(), ctx.account(), late.id(), 0, PatternStatus.CONFIRMED);
        UUID lateFact = payment(ctx, ORIGIN.minusDays(1), "999.00", Direction.IN, CLOCK.instant());
        UUID futureFact = payment(ctx, ORIGIN.plusDays(1), "888.00", Direction.IN, CLOCK.instant());
        payment(ctx, ORIGIN.plusDays(1), "888.00", Direction.OUT, CLOCK.instant());
        CLOCK.set(NOW);

        var built = build(ctx, "2026-09-01", "2026-09-01", 7, "2026-10-01", "WITH_KNOWN_PLANS");
        var input = built.snapshot().originSnapshots().get(0);
        assertThat(input.payments()).hasSize(14).noneMatch(p -> p.paymentId().equals(lateFact.toString())
                || p.paymentId().equals(futureFact.toString()));
        assertThat(input.payments()).filteredOn(p -> p.paymentId().equals(ctx.trainingIn().get(0).toString()))
                .extracting(p -> p.categoryCode()).containsExactly("SALES");
        assertThat(input.knownPlans()).hasSize(1);
        assertThat(input.knownPlans().get(0).id()).isEqualTo(plan);
        assertThat(input.knownPlans().get(0).remainingAmount()).isEqualTo("600.00");
        assertThat(input.recurringPatterns()).hasSize(1);
        assertThat(input.recurringPatterns().get(0).id()).isEqualTo(early.id().toString());
        assertThat(built.snapshot().actualDays().get(0).inflow()).isEqualTo("888.00");
        assertThat(build(ctx, "2026-09-01", "2026-09-01", 7, "2026-10-01", "HISTORY_ONLY")
                .snapshot().originSnapshots().get(0).knownPlans()).isEmpty();
    }

    @Test
    void experimentSettingsOverrideBothHistoricalAndCurrentAccountSettings() throws Exception {
        var ctx = seed(30);
        CLOCK.set(Instant.parse("2026-09-01T10:00:00Z"));
        assertThat(send("PUT", base(ctx) + "/forecast-settings", ctx.owner(),
                "{\"windowWeeks\":10,\"horizonDays\":30,\"bufferAmount\":\"99999.00\",\"scenarioCount\":2000,"
                        + "\"minCalibrationOrigins\":12,\"calibrationStepDays\":7,\"algorithm\":\"WEEKDAY_MEAN_V1\"}",
                "If-Match", "\"0\"").status()).isEqualTo(200);
        CLOCK.set(NOW);
        var request = new BacktestRqW(ctx.account().toString(), "2026-09-01", "2026-09-01", 7,
                "HISTORY_ONLY", 8, false);
        var settings = new ForecastSettings(8, 30, new BigDecimal("50.00"), 2000, 12, 7, ForecastSettings.ALGORITHM);
        var built = service.build(access.requireRead(ctx.owner(), ctx.company()), ctx.account(),
                LocalDate.parse("2026-10-01"), request, settings);
        assertThat(built.snapshot().settings().windowWeeks()).isEqualTo(8);
        assertThat(built.snapshot().settings().bufferAmount()).isEqualTo("50.00");
        assertThat(built.snapshot().originSnapshots().get(0).settings()).isEqualTo(built.snapshot().settings());
    }

    @Test
    void usesExclusiveCompanyTimezoneCutoffsForBothTrainingAndScoring() throws Exception {
        var ctx = seed(30);
        Instant boundary = Instant.parse("2026-09-01T21:00:00Z");
        UUID before = payment(ctx, ORIGIN, "1.00", Direction.IN, boundary.minusNanos(1000));
        payment(ctx, ORIGIN, "1.00", Direction.OUT, boundary.minusNanos(1000));
        UUID at = payment(ctx, ORIGIN, "2.00", Direction.IN, boundary);
        payment(ctx, ORIGIN.plusDays(1), "3.00", Direction.IN, Instant.parse("2026-10-01T21:00:00Z"));
        ledger.insertAnchor(access.requireWrite(ctx.owner(), ctx.company()), new BalanceAnchor(UUID.randomUUID(),
                ctx.account(), ORIGIN.plusDays(1), new BigDecimal("999999.00"), AnchorSource.MANUAL,
                "Поздний якорь", Instant.parse("2026-10-01T21:00:00Z"), ctx.owner(), null), null);
        var built = build(ctx, "2026-09-01", "2026-09-01", 7, "2026-10-01", "HISTORY_ONLY");
        assertThat(built.snapshot().originSnapshots().get(0).payments()).extracting(p -> p.paymentId())
                .contains(before.toString()).doesNotContain(at.toString());
        assertThat(built.snapshot().actualDays().get(0).inflow()).isEqualTo("0.00");
        assertThat(built.snapshot().actualDays().get(0).balanceEod()).isEqualTo("100000.00");
    }

    @Test
    void honorsACompanyTimezoneOtherThanMoscow() throws Exception {
        var ctx = seed(30);
        assertThat(send("PUT", "/api/v1/orchestrator/companies/" + ctx.company(), ctx.owner(),
                "{\"name\":\"UTC\",\"timezone\":\"UTC\"}", "If-Match", "\"0\"").status()).isEqualTo(200);
        UUID evening = payment(ctx, ORIGIN, "1.00", Direction.IN, Instant.parse("2026-09-01T22:00:00Z"));
        payment(ctx, ORIGIN, "1.00", Direction.OUT, Instant.parse("2026-09-01T22:00:00Z"));
        payment(ctx, ORIGIN.plusDays(1), "3.00", Direction.IN, Instant.parse("2026-10-01T22:00:00Z"));
        payment(ctx, ORIGIN.plusDays(1), "3.00", Direction.OUT, Instant.parse("2026-10-01T22:00:00Z"));
        var built = build(ctx, "2026-09-01", "2026-09-01", 7, "2026-10-01", "HISTORY_ONLY");
        assertThat(built.snapshot().originSnapshots().get(0).payments()).extracting(p -> p.paymentId())
                .contains(evening.toString());
        assertThat(built.snapshot().actualDays().get(0).inflow()).isEqualTo("3.00");
        assertThat(json.readTree(built.provenance()).path("scoringKnownBefore").asText())
                .isEqualTo("2026-10-02T00:00:00Z");
    }

    @Test
    void skipsTrainingThatCannotSupportEveryWeekday() throws Exception {
        var ctx = seed(30);
        ledger.insertCompleteness(access.requireWrite(ctx.owner(), ctx.company()), new CompletenessRange(
                UUID.randomUUID(), ctx.account(), ORIGIN.minusDays(6), ORIGIN.minusDays(6), Completeness.UNKNOWN,
                "Пропущен единственный weekday", Instant.parse("2026-09-01T19:00:00Z"), ctx.owner()));
        assertThatThrownBy(() -> build(ctx, "2026-09-01", "2026-09-01", 7, "2026-10-01", "HISTORY_ONLY"))
                .isInstanceOfSatisfying(BacktestSnapshotException.class, e -> assertThat(e.origins().get(0).reason())
                        .isEqualTo("INSUFFICIENT_HISTORY"));
    }

    @Test
    void aKnownZeroScoringBalanceIsUsable() throws Exception {
        var ctx = seed(30);
        var scope = access.requireWrite(ctx.owner(), ctx.company());
        payment(ctx, ORIGIN.plusDays(1), "100000.00", Direction.OUT, Instant.parse("2026-09-02T18:00:00Z"));
        for (int offset = 1; offset <= 30; offset++) {
            ledger.insertAnchor(scope, new BalanceAnchor(UUID.randomUUID(), ctx.account(), ORIGIN.plusDays(offset),
                    new BigDecimal("0.00"), AnchorSource.MANUAL, "Подтверждённый нулевой остаток",
                    Instant.parse("2026-10-01T19:00:00Z"), ctx.owner(), null), null);
        }
        var built = build(ctx, "2026-09-01", "2026-09-01", 7, "2026-10-01", "HISTORY_ONLY");
        assertThat(built.snapshot().actualDays()).hasSize(30)
                .allSatisfy(day -> assertThat(day.balanceEod()).isEqualTo("0.00"));
    }

    @Test
    void concurrentChangeCannotMixANewAnchorWithThePreviouslyReadDataVersion() throws Exception {
        var ctx = seed(30);
        var scope = access.requireRead(ctx.owner(), ctx.company());
        long originalVersion = companies.findCompany(scope).orElseThrow().dataVersion();
        var companyRead = new CountDownLatch(1);
        var continueBuild = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            companyRead.countDown();
            assertThat(continueBuild.await(10, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(companies).findCompany(any(CompanyScope.class));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = CompletableFuture.supplyAsync(() -> build(ctx, "2026-09-01", "2026-09-01", 7,
                    "2026-10-01", "HISTORY_ONLY"), executor);
            try {
                assertThat(companyRead.await(10, TimeUnit.SECONDS)).isTrue();
                ledger.insertAnchor(scope, new BalanceAnchor(UUID.randomUUID(), ctx.account(), ORIGIN,
                        new BigDecimal("100001.00"), AnchorSource.MANUAL, "Конкурентная запись",
                        Instant.parse("2026-09-01T20:00:00Z"), ctx.owner(), null), null);
                companies.incrementDataVersion(scope, NOW);
                continueBuild.countDown();
                var built = future.get(20, TimeUnit.SECONDS);
                assertThat(built.snapshot().inputDataVersion()).isEqualTo(originalVersion);
                assertThat(built.snapshot().originSnapshots().get(0).startBalance()).isEqualTo("100000.00");
                assertThat(build(ctx, "2026-09-01", "2026-09-01", 7, "2026-10-01", "HISTORY_ONLY")
                        .snapshot().originSnapshots().get(0).startBalance()).isEqualTo("100001.00");
            } finally {
                continueBuild.countDown();
            }
        }
    }

    @Test
    void skipsUnrecoverableOriginInsteadOfReplacingItWithTodaysHistory() throws Exception {
        var ctx = seed(37, TrainState.LATE_IMPORT, null);
        var built = build(ctx, "2026-09-01", "2026-09-08", 7, "2026-10-08", "HISTORY_ONLY");
        assertThat(built.snapshot().originSnapshots()).extracting(s -> s.asOfDate()).containsExactly("2026-09-08");
        assertThat(built.origins().get(0).status()).isEqualTo(Status.SKIPPED);
        assertThat(built.origins().get(0).reason()).isEqualTo("INSUFFICIENT_HISTORY");
    }

    @Test
    void skipsIncompleteScoringAndIncludesOnlyTheUnionOfUsableHorizons() throws Exception {
        var ctx = seed(37);
        ledger.insertCompleteness(access.requireWrite(ctx.owner(), ctx.company()), new CompletenessRange(
                UUID.randomUUID(), ctx.account(), LocalDate.parse("2026-09-03"), LocalDate.parse("2026-09-03"),
                Completeness.UNKNOWN, "Неполная выгрузка", Instant.parse("2026-10-08T19:00:00Z"), ctx.owner()));
        var built = build(ctx, "2026-09-01", "2026-09-08", 7, "2026-10-08", "HISTORY_ONLY");
        assertThat(built.origins().get(0).reason()).isEqualTo("INCOMPLETE_SCORING");
        assertThat(built.snapshot().originSnapshots()).extracting(s -> s.asOfDate()).containsExactly("2026-09-08");
        assertThat(built.snapshot().actualDays()).hasSize(30);
        assertThat(built.snapshot().actualDays().get(0).date()).isEqualTo("2026-09-09");
    }

    @Test
    void missingEodIsNotConvertedToZeroAndAllSkippedOriginsAreAnError() throws Exception {
        var ctx = seed(30, TrainState.AVAILABLE, LocalDate.parse("2026-09-03"));
        assertThatThrownBy(() -> build(ctx, "2026-09-01", "2026-09-01", 7, "2026-10-01", "HISTORY_ONLY"))
                .isInstanceOfSatisfying(BacktestSnapshotException.class, e -> {
                    assertThat(e.code()).isEqualTo("NO_EVALUABLE_ORIGINS");
                    assertThat(e.origins()).hasSize(1);
                    assertThat(e.origins().get(0).reason()).isEqualTo("INCOMPLETE_SCORING");
                });
        assertThat(jdbc.sql("select count(*) from snapshots").query(Long.class).single()).isZero();
    }

    @Test
    void missingHistoricalBalanceAndImmatureHorizonAreReportedSeparately() throws Exception {
        var ctx = seed(30, TrainState.NO_BALANCE, null);
        assertThatThrownBy(() -> build(ctx, "2026-09-01", "2026-09-08", 7, "2026-10-01", "HISTORY_ONLY"))
                .isInstanceOfSatisfying(BacktestSnapshotException.class, e -> assertThat(e.origins())
                        .extracting(o -> o.reason()).containsExactly("NO_START_BALANCE", "IMMATURE_ORIGIN"));
    }

    @Test
    void tenantBoundaryIsCheckedBeforeReadingFinancialHistory() throws Exception {
        var ctx = seed(30);
        UUID otherOwner = UUID.randomUUID();
        UUID otherCompany = createCompany(otherOwner, "Чужая компания");
        UUID otherAccount = createAccount(otherOwner, otherCompany, null);
        var request = new BacktestRqW(otherAccount.toString(), "2026-09-01", "2026-09-01", 7, "HISTORY_ONLY", 12, false);
        assertThatThrownBy(() -> service.build(access.requireRead(ctx.owner(), ctx.company()), otherAccount,
                LocalDate.parse("2026-10-01"), request, ForecastSettings.defaults()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void validatesRequestAndRejectsAnOpenOuterCutoff() throws Exception {
        var ctx = seed(30);
        var scope = access.requireRead(ctx.owner(), ctx.company());
        for (BacktestRqW request : List.of(
                new BacktestRqW(ctx.account().toString(), "2026-09-02", "2026-09-01", 7, "HISTORY_ONLY", 12, false),
                new BacktestRqW(ctx.account().toString(), "2026-09-01", "2026-09-01", 6, "HISTORY_ONLY", 12, false),
                new BacktestRqW(ctx.account().toString(), "2026-09-01", "2026-09-01", 31, "HISTORY_ONLY", 12, false),
                new BacktestRqW(ctx.account().toString(), "2026-02-30", "2026-09-01", 7, "HISTORY_ONLY", 12, false),
                new BacktestRqW(UUID.randomUUID().toString(), "2026-09-01", "2026-09-01", 7, "HISTORY_ONLY", 12, false),
                new BacktestRqW(ctx.account().toString(), "2026-09-01", "2026-09-01", 7, "UNKNOWN", 12, false),
                new BacktestRqW(ctx.account().toString(), "2026-09-01", "2026-09-01", 7, "HISTORY_ONLY", 8, false))) {
            assertThatThrownBy(() -> service.build(scope, ctx.account(), LocalDate.parse("2026-10-01"), request,
                    ForecastSettings.defaults())).isInstanceOf(SnapshotException.class);
        }
        assertThatThrownBy(() -> service.build(scope, ctx.account(), LocalDate.parse("2026-11-01"),
                new BacktestRqW(ctx.account().toString(), "2026-09-01", "2026-09-01", 7, "HISTORY_ONLY", 12, false),
                ForecastSettings.defaults())).isInstanceOf(SnapshotException.class);
    }

    @Test
    void rejectsARequestedGridLargerThanTheForecastContractAllows() throws Exception {
        var ctx = seed(30);
        var request = new BacktestRqW(ctx.account().toString(), ORIGIN.minusDays(100L * 7).toString(),
                ORIGIN.toString(), 7, "HISTORY_ONLY", 12, false);
        assertThatThrownBy(() -> service.build(access.requireRead(ctx.owner(), ctx.company()), ctx.account(),
                ORIGIN.plusDays(30), request, ForecastSettings.defaults()))
                .isInstanceOfSatisfying(SnapshotException.class,
                        e -> assertThat(e.code()).isEqualTo("INVALID_BACKTEST_REQUEST"));
    }

    @Test
    void storedBacktestIsImmutableAndDoesNotReadTodaysDailyProjection() throws Exception {
        var ctx = seed(30);
        jdbc.sql("insert into daily_observations (company_id, account_id, date, inflow, outflow, eod, status, "
                        + "balance_mismatch, input_version, payment_count) values (:company, :account, :date, "
                        + "999999, 0, 999999, 'OBSERVED', false, 999, 1)")
                .param("company", ctx.company()).param("account", ctx.account()).param("date", ORIGIN).update();
        var scope = access.requireRead(ctx.owner(), ctx.company());
        var built = build(ctx, "2026-09-01", "2026-09-01", 7, "2026-10-01", "HISTORY_ONLY");
        var stored = service.store(scope, built);
        assertThat(stored.kind()).isEqualTo("BACKTEST");
        assertThat(stored.asOf()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(built.snapshot().originSnapshots().get(0).startBalance()).isEqualTo("100000.00");
        assertThat(send("PUT", base(ctx) + "/payments/" + ctx.trainingIn().get(0) + "/category", ctx.owner(),
                "{\"categoryCode\":\"REFUND\"}", "If-Match", "\"0\"").status()).isEqualTo(200);
        var reread = snapshots.find(scope, stored.id()).orElseThrow();
        assertThat(json.readTree(reread.payload())).isEqualTo(json.readTree(stored.payload()));
        assertThat(Fingerprint.of(json.readTree(reread.payload()))).isEqualTo(stored.fingerprint());
        assertThatThrownBy(() -> jdbc.sql("update snapshots set as_of = as_of where id = :id")
                .param("id", stored.id()).update()).hasMessageContaining("immutable");
        var newer = build(ctx, "2026-09-01", "2026-09-01", 7, "2026-10-01", "HISTORY_ONLY");
        assertThat(newer.snapshot().inputDataVersion()).isGreaterThan(stored.inputVersion());
        assertThat(newer.fingerprint()).isNotEqualTo(stored.fingerprint());
    }

    private BacktestSnapshotService.Built build(Ctx ctx, String first, String last, int step, String outer, String mode) {
        return service.build(access.requireRead(ctx.owner(), ctx.company()), ctx.account(), LocalDate.parse(outer),
                new BacktestRqW(ctx.account().toString(), first, last, step, mode, 12, false), ForecastSettings.defaults());
    }

    private Ctx seed(int scoringDays) throws Exception {
        return seed(scoringDays, TrainState.AVAILABLE, null);
    }

    private Ctx seed(int scoringDays, TrainState trainState, LocalDate missingEod) throws Exception {
        CLOCK.set(Instant.parse("2026-08-25T09:00:00Z"));
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Historical snapshots");
        UUID account = createAccount(owner, company, null);
        var ctx = new Ctx(owner, company, account, new ArrayList<>());
        var scope = access.requireWrite(owner, company);
        for (int i = 0; i < 7; i++) {
            LocalDate day = ORIGIN.minusDays(6).plusDays(i);
            Instant known = trainState == TrainState.LATE_IMPORT ? Instant.parse("2026-09-02T18:00:00Z")
                    : Instant.parse(day + "T18:00:00Z");
            ctx.trainingIn().add(payment(ctx, day, "10000.00", Direction.IN, known));
            payment(ctx, day, "8000.00", Direction.OUT, known);
            if (trainState != TrainState.NO_BALANCE) {
                ledger.insertAnchor(scope, new BalanceAnchor(UUID.randomUUID(), account, day,
                        new BigDecimal("88000.00").add(new BigDecimal("2000.00").multiply(BigDecimal.valueOf(i))),
                        AnchorSource.STATEMENT, "Синтетический train", known, null, null), null);
            }
        }
        Instant trainKnown = Instant.parse(trainState == TrainState.LATE_IMPORT
                ? "2026-09-02T18:00:00Z" : "2026-09-01T18:00:00Z");
        ledger.insertCompleteness(scope, new CompletenessRange(UUID.randomUUID(), account, ORIGIN.minusDays(6),
                ORIGIN, Completeness.CONFIRMED_COMPLETE, "Синтетический train", trainKnown, null));
        LocalDate end = ORIGIN.plusDays(scoringDays);
        for (LocalDate day = ORIGIN.plusDays(1); !day.isAfter(end); day = day.plusDays(1)) {
            if (!day.equals(missingEod)) {
                ledger.insertAnchor(scope, new BalanceAnchor(UUID.randomUUID(), account, day,
                        new BigDecimal("100000.00"), AnchorSource.STATEMENT, "Синтетический scoring",
                        Instant.parse(day + "T18:00:00Z"), null, null), null);
            }
        }
        ledger.insertCompleteness(scope, new CompletenessRange(UUID.randomUUID(), account, ORIGIN.plusDays(1), end,
                Completeness.CONFIRMED_COMPLETE, "Синтетический scoring", Instant.parse(end + "T18:00:00Z"), null));
        CLOCK.set(NOW);
        return ctx;
    }

    private UUID payment(Ctx ctx, LocalDate date, String amount, Direction direction, Instant known) {
        UUID id = UUID.randomUUID();
        ledger.insertPayment(new Payment(id, ctx.company(), ctx.account(), null, null, null, date,
                new BigDecimal(amount), direction, direction == Direction.IN ? "client-a" : "supplier-a",
                null, null, null, direction == Direction.IN ? "SALES" : "SUPPLIERS", CategorySource.RULE,
                "R1", null, 0, known, known));
        return id;
    }

    private String plan(Ctx ctx, String title) throws Exception {
        var response = send("POST", base(ctx) + "/expected-payments", ctx.owner(), "{\"title\":\"" + title
                + "\",\"direction\":\"IN\",\"amount\":\"1000.00\",\"dueDate\":\"2026-09-15\","
                + "\"categoryCode\":\"SALES\",\"relation\":\"ADDITIONAL\",\"source\":\"INVOICE\"}");
        assertThat(response.status()).isEqualTo(201);
        return response.body().path("id").asText();
    }

    private static DetectedCandidate candidate(String key) {
        return new DetectedCandidate(Direction.OUT, "RENT", key,
                new Schedule(Frequency.MONTHLY, null, 5, BusinessDayRule.NONE), new BigDecimal("500.00"), 5,
                LocalDate.parse("2026-05-01"), ORIGIN);
    }

    private static String base(Ctx ctx) {
        return "/api/v1/orchestrator/companies/" + ctx.company() + "/accounts/" + ctx.account();
    }

    private static List<String> fields(JsonNode node) {
        List<String> result = new ArrayList<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private enum TrainState { AVAILABLE, LATE_IMPORT, NO_BALANCE }

    private record Ctx(UUID owner, UUID company, UUID account, List<UUID> trainingIn) {
    }
}
