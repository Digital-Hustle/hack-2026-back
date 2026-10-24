package ru.cashflow.orchestrator.integrations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.jobs.application.JobService;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.AnchorSource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.features.settings.application.SettingsService;
import ru.cashflow.orchestrator.features.settings.model.ForecastSettings;
import ru.cashflow.orchestrator.features.snapshot.application.BacktestSnapshotService;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.BacktestRqW;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;
import ru.cashflow.orchestrator.support.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

@Import(NestedCalibrationIntegrationTest.ClockConfig.class)
class NestedCalibrationIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate ORIGIN = LocalDate.parse("2026-09-01");
    private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-01T09:00:00Z"));

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    BacktestSnapshotService backtests;
    @Autowired
    CompanyAccess access;
    @Autowired
    LedgerRepository ledger;
    @Autowired
    SettingsService settings;
    @Autowired
    JobService jobs;
    @Autowired
    SnapshotRepository snapshots;

    @Test
    void buildsTwelveMatureInputsWithIndependentTrainWindowsAndNoRecursiveCalibration() throws Exception {
        var ctx = seed(200);
        var built = build(ctx, "2026-09-01", "2026-09-01", "2026-10-01", ForecastSettings.defaults());
        var outer = built.snapshot().originSnapshots().getFirst();
        var origins = outer.calibrationOrigins();
        assertThat(built.snapshot().request().evaluateRisk()).isTrue();
        assertThat(outer.historyFrom()).isEqualTo("2026-06-10");
        assertThat(origins).hasSize(12);
        assertThat(origins.getFirst().input().asOfDate()).isEqualTo("2026-05-17");
        assertThat(origins.getFirst().input().historyFrom()).isEqualTo("2026-02-23");
        assertThat(origins.getLast().input().asOfDate()).isEqualTo("2026-08-02");
        assertThat(origins.getLast().actualDays().getLast().date()).isEqualTo("2026-09-01");
        assertThat(origins).allSatisfy(origin -> {
            var input = origin.input();
            assertThat(input.calibrationOrigins()).isEmpty();
            assertThat(input.companyId()).isEqualTo(ctx.company().toString());
            assertThat(input.accountId()).isEqualTo(ctx.account().toString());
            assertThat(input.scope()).isEqualTo("WITH_KNOWN_PLANS");
            assertThat(input.settings().windowWeeks()).isEqualTo(12);
            assertThat(input.inputDataVersion()).isEqualTo(built.snapshot().inputDataVersion());
            assertThat(input.dailyHistory()).hasSize(84);
            assertThat(input.payments()).allMatch(p -> p.date().compareTo(input.asOfDate()) <= 0);
            assertThat(origin.actualDays()).hasSize(30).allSatisfy(day -> {
                assertThat(day.inflow()).isEqualTo("10.00");
                assertThat(day.outflow()).isEqualTo("5.00");
            });
            assertThat(origin.actualPayments()).hasSize(60).allMatch(p -> p.date().compareTo(input.asOfDate()) > 0);
            assertThat(Fingerprint.of(json.valueToTree(input))).isEqualTo(input.fingerprint());
        });
        assertThat(Fingerprint.of(json.valueToTree(outer))).isEqualTo(outer.fingerprint());
        var again = build(ctx, "2026-09-01", "2026-09-01", "2026-10-01", ForecastSettings.defaults());
        assertThat(again.fingerprint()).isEqualTo(built.fingerprint());
        assertThat(json.readTree(built.provenance()).path("origins").get(0).path("inputProvenance")
                .path("calibration").path("includedCount").asInt()).isEqualTo(12);
    }

    @Test
    void eachOuterOriginUsesItsOwnKnowledgeCutoffForCalibrationOutcomes() throws Exception {
        var ctx = seed(200);
        UUID late = payment(ctx, LocalDate.parse("2026-08-15"), Direction.IN, "777.00", "R1",
                Instant.parse("2026-09-05T18:00:00Z"));
        var built = build(ctx, "2026-09-01", "2026-09-08", "2026-10-08", ForecastSettings.defaults());
        var first = built.snapshot().originSnapshots().getFirst();
        var second = built.snapshot().originSnapshots().getLast();
        assertThat(first.calibrationOrigins()).hasSize(12).allSatisfy(origin -> {
            assertThat(origin.actualDays()).allMatch(d -> d.date().compareTo("2026-09-01") <= 0);
            assertThat(origin.actualPayments()).noneMatch(p -> p.paymentId().equals(late.toString()));
        });
        assertThat(second.calibrationOrigins().getLast().input().asOfDate()).isEqualTo("2026-08-09");
        var day = second.calibrationOrigins().getLast().actualDays().stream()
                .filter(d -> d.date().equals("2026-08-15")).findFirst().orElseThrow();
        assertThat(day.inflow()).isEqualTo("787.00");
        assertThat(second.calibrationOrigins().getLast().actualPayments())
                .anyMatch(p -> p.paymentId().equals(late.toString()));
    }

    @Test
    void shortHistoryDoesNotPadOrRejectThePointForecastWhenRiskHasTooFewOrigins() throws Exception {
        var ctx = seed(60);
        var outer = build(ctx, "2026-09-01", "2026-09-01", "2026-10-01", ForecastSettings.defaults())
                .snapshot().originSnapshots().getFirst();
        assertThat(outer.calibrationOrigins()).extracting(c -> c.input().asOfDate())
                .containsExactly("2026-07-12", "2026-07-19", "2026-07-26", "2026-08-02");
        assertThat(outer.settings().minCalibrationOrigins()).isEqualTo(12);
    }

    @Test
    void missingCalibrationFlowsAreSkippedAndOlderValidOriginsFillTheTarget() throws Exception {
        var ctx = seed(200);
        completeness(ctx, LocalDate.parse("2026-08-20"), Completeness.UNKNOWN,
                Instant.parse("2026-08-25T18:00:00Z"));
        // This repair is visible to the outer backtest scorer, but not to the 1 September calibration.
        completeness(ctx, LocalDate.parse("2026-08-20"), Completeness.CONFIRMED_COMPLETE,
                Instant.parse("2026-09-02T18:00:00Z"));
        var built = build(ctx, "2026-09-01", "2026-09-01", "2026-10-01", ForecastSettings.defaults());
        assertThat(built.snapshot().originSnapshots().getFirst().calibrationOrigins()).hasSize(12)
                .allSatisfy(c -> assertThat(c.actualDays()).noneMatch(d -> d.date().equals("2026-08-20")));
        var traces = json.readTree(built.provenance()).path("origins").get(0).path("inputProvenance")
                .path("calibration").path("origins");
        assertThat(traces).anySatisfy(t -> assertThat(t.path("reason").asText()).isEqualTo("INCOMPLETE_SCORING"));
    }

    @Test
    void scoringCategoriesIgnoreUserCorrectionsMadeAfterTheInnerOrigin() throws Exception {
        var ctx = seed(200);
        UUID scored = ctx.incomes().get(LocalDate.parse("2026-08-10"));
        CLOCK.set(Instant.parse("2026-08-20T12:00:00Z"));
        assertThat(send("PUT", base(ctx) + "/payments/" + scored + "/category", ctx.owner(),
                "{\"categoryCode\":\"REFUND\"}", "If-Match", "\"0\"").status()).isEqualTo(200);
        CLOCK.set(Instant.parse("2026-11-01T09:00:00Z"));
        var latest = build(ctx, "2026-09-01", "2026-09-01", "2026-10-01", ForecastSettings.defaults())
                .snapshot().originSnapshots().getFirst().calibrationOrigins().getLast();
        assertThat(latest.input().asOfDate()).isEqualTo("2026-08-02");
        assertThat(latest.actualPayments()).filteredOn(p -> p.paymentId().equals(scored.toString()))
                .singleElement().satisfies(p -> assertThat(p.categoryCode()).isEqualTo("SALES"));
    }

    @Test
    void scoringCannotUseAnAutomaticRuleVersionFirstSeenAfterTheInnerOrigin() throws Exception {
        var ctx = seed(200);
        UUID unavailable = payment(ctx, LocalDate.parse("2026-08-10"), Direction.IN, "123.00", "R2",
                Instant.parse("2026-08-10T18:00:00Z"));
        var built = build(ctx, "2026-09-01", "2026-09-01", "2026-10-01", ForecastSettings.defaults());
        assertThat(built.snapshot().originSnapshots().getFirst().calibrationOrigins()).hasSize(12)
                .allSatisfy(c -> assertThat(c.actualPayments())
                        .noneMatch(p -> p.paymentId().equals(unavailable.toString())));
        var traces = json.readTree(built.provenance()).path("origins").get(0).path("inputProvenance")
                .path("calibration").path("origins");
        assertThat(traces).anySatisfy(t -> assertThat(t.path("reason").asText()).isEqualTo("SCORING_RULES_UNAVAILABLE"));
    }

    @Test
    void fixedExperimentSettingsReachEveryInnerInputInsteadOfHistoricalAccountSettings() throws Exception {
        var ctx = seed(200);
        CLOCK.set(Instant.parse("2026-04-01T12:00:00Z"));
        settings.update(ctx.owner(), ctx.company(), ctx.account(), 0,
                new ForecastSettings(10, 30, new BigDecimal("999.00"), 500, 20, 7, ForecastSettings.ALGORITHM));
        CLOCK.set(Instant.parse("2026-11-01T09:00:00Z"));
        var experiment = new ForecastSettings(8, 30, new BigDecimal("50.00"), 100, 12, 7, ForecastSettings.ALGORITHM);
        var outer = build(ctx, "2026-09-01", "2026-09-01", "2026-10-01", experiment)
                .snapshot().originSnapshots().getFirst();
        assertThat(outer.calibrationOrigins()).hasSize(12).allSatisfy(c -> {
            assertThat(c.input().settings().windowWeeks()).isEqualTo(8);
            assertThat(c.input().settings().bufferAmount()).isEqualTo("50.00");
            assertThat(c.input().settings().scenarioCount()).isEqualTo(100);
            assertThat(c.input().dailyHistory()).hasSize(56);
        });
    }

    @Test
    void currentForecastJobStoresCalibrationAtomicallyAndKeepsItImmutable() throws Exception {
        var ctx = seed(200);
        var job = jobs.createForecast(ctx.owner(), ctx.company(), ctx.account(), "nested-job-1", ORIGIN, "HISTORY_ONLY");
        var scope = access.requireRead(ctx.owner(), ctx.company());
        var stored = snapshots.find(scope, job.snapshotId()).orElseThrow();
        var payload = json.readTree(stored.payload());
        assertThat(payload.path("calibrationOrigins")).hasSize(12);
        assertThat(Fingerprint.of(payload)).isEqualTo(stored.fingerprint());
        assertThat(jobs.createForecast(ctx.owner(), ctx.company(), ctx.account(), "nested-job-1", ORIGIN, "HISTORY_ONLY").id())
                .isEqualTo(job.id());
        payment(ctx, LocalDate.parse("2026-08-15"), Direction.IN, "999.00", "R1",
                Instant.parse("2026-10-01T18:00:00Z"));
        assertThat(json.readTree(snapshots.find(scope, stored.id()).orElseThrow().payload())).isEqualTo(payload);
        assertThat(json.readTree(stored.provenance()).path("calibration").path("includedCount").asInt()).isEqualTo(12);
    }

    private BacktestSnapshotService.Built build(Ctx ctx, String first, String last, String outer,
                                                ForecastSettings experiment) {
        return backtests.build(access.requireRead(ctx.owner(), ctx.company()), ctx.account(), LocalDate.parse(outer),
                new BacktestRqW(ctx.account().toString(), first, last, 7, "WITH_KNOWN_PLANS",
                        experiment.windowWeeks(), true), experiment);
    }

    private Ctx seed(int trainDays) throws Exception {
        CLOCK.set(ORIGIN.minusDays(trainDays + 1L).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Nested calibration");
        UUID account = createAccount(owner, company, null);
        var ctx = new Ctx(owner, company, account, new HashMap<>());
        var scope = access.requireWrite(owner, company);
        for (LocalDate day = ORIGIN.minusDays(trainDays); !day.isAfter(ORIGIN.plusDays(37)); day = day.plusDays(1)) {
            Instant known = Instant.parse(day + "T18:00:00Z");
            ctx.incomes().put(day, payment(ctx, day, Direction.IN, "10.00", "R1", known));
            payment(ctx, day, Direction.OUT, "5.00", "R1", known);
            ledger.insertAnchor(scope, new BalanceAnchor(UUID.randomUUID(), account, day,
                    new BigDecimal("100000.00"), AnchorSource.STATEMENT, "Синтетический EOD", known, null, null), null);
            completeness(ctx, day, Completeness.CONFIRMED_COMPLETE, known);
        }
        CLOCK.set(Instant.parse("2026-11-01T09:00:00Z"));
        return ctx;
    }

    private UUID payment(Ctx ctx, LocalDate date, Direction direction, String amount, String rule, Instant known) {
        UUID id = UUID.randomUUID();
        ledger.insertPayment(new Payment(id, ctx.company(), ctx.account(), null, null, null, date,
                new BigDecimal(amount), direction, direction == Direction.IN ? "client-a" : "supplier-a",
                null, null, null, direction == Direction.IN ? "SALES" : "SUPPLIERS", CategorySource.RULE,
                rule, null, 0, known, known));
        return id;
    }

    private void completeness(Ctx ctx, LocalDate day, Completeness state, Instant known) {
        ledger.insertCompleteness(access.requireWrite(ctx.owner(), ctx.company()),
                new CompletenessRange(UUID.randomUUID(), ctx.account(), day, day, state,
                        "Синтетическая полнота", known, null));
    }

    private String base(Ctx ctx) {
        return "/api/v1/orchestrator/companies/" + ctx.company() + "/accounts/" + ctx.account();
    }

    private record Ctx(UUID owner, UUID company, UUID account, Map<LocalDate, UUID> incomes) {
    }
}
