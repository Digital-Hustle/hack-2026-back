package ru.cashflow.orchestrator.integrations;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.features.snapshot.application.SnapshotService;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;
import ru.cashflow.orchestrator.features.snapshot.command.SnapshotException;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;
import ru.cashflow.orchestrator.support.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(SnapshotIntegrationTest.ClockConfig.class)
class SnapshotIntegrationTest extends AbstractIntegrationTest {

    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-20T09:00:00Z"));

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    SnapshotService service;
    @Autowired
    SnapshotRepository snapshots;
    @Autowired
    CompanyAccess access;
    @Autowired
    LedgerRepository ledger;
    @Autowired
    TransactionTemplate tx;

    @Test
    void reproducesHandoffCalculateFixtureShapeAndFingerprintIsStableAcrossBuilds() throws Exception {
        var ctx = ctxWithSevenDays();
        String base = "/api/v1/orchestrator/companies/" + ctx.company() + "/accounts/" + ctx.account();
        assertThat(send("POST", base + "/expected-payments", ctx.owner(), "{\"title\":\"Дополнительная оплата\","
                + "\"direction\":\"OUT\",\"amount\":\"160000.00\",\"dueDate\":\"2026-10-29\",\"categoryCode\":\"SUPPLIERS\","
                + "\"counterpartyKey\":\"supplier-a\",\"relation\":\"ADDITIONAL\",\"source\":\"MANUAL\"}").status())
                .isEqualTo(201);
        CLOCK.set(Instant.parse("2026-10-25T09:00:00Z"));

        var first = build(ctx);
        var second = build(ctx);
        JsonNode built = json.valueToTree(first.snapshot());
        JsonNode fixture = fixture();

        assertThat(fieldNames(built)).containsExactlyInAnyOrderElementsOf(fieldNames(fixture));
        assertThat(fieldNames(built.path("dailyHistory").get(0)))
                .containsExactlyInAnyOrderElementsOf(fieldNames(fixture.path("dailyHistory").get(0)));
        assertThat(fieldNames(built.path("payments").get(0)))
                .containsExactlyInAnyOrderElementsOf(fieldNames(fixture.path("payments").get(0)));
        assertThat(fieldNames(built.path("knownPlans").get(0)))
                .containsExactlyInAnyOrderElementsOf(fieldNames(fixture.path("knownPlans").get(0)));
        assertThat(fieldNames(built.path("completeRanges").get(0)))
                .containsExactlyInAnyOrderElementsOf(fieldNames(fixture.path("completeRanges").get(0)));
        assertThat(fieldNames(built.path("settings")))
                .containsExactlyInAnyOrderElementsOf(fieldNames(fixture.path("settings")));

        assertThat(built.path("asOfDate").asText()).isEqualTo("2026-10-24");
        assertThat(built.path("historyFrom").asText()).isEqualTo("2026-10-18");
        assertThat(built.path("startBalance").asText()).isEqualTo("100000.00");
        assertThat(built.path("startBalanceDate").asText()).isEqualTo("2026-10-24");
        assertThat(built.path("dailyHistory")).hasSize(7);
        assertThat(built.path("dailyHistory").get(0).path("inflow").asText()).isEqualTo("10000.00");
        assertThat(built.path("payments")).hasSize(14);
        assertThat(built.path("knownPlans").get(0).path("remainingAmount").asText()).isEqualTo("160000.00");
        assertThat(built.path("calibrationOrigins").isArray()).isTrue();
        assertThat(built.path("recurringPatterns").isArray()).isTrue();

        assertThat(first.snapshot().snapshotId()).isNotEqualTo(second.snapshot().snapshotId());
        assertThat(first.snapshot().fingerprint()).isEqualTo(second.snapshot().fingerprint());
        assertThat(Fingerprint.of(built)).isEqualTo(first.snapshot().fingerprint());
    }

    @Test
    void historyOnlyDropsPlansAndOpenDayIsRejected() throws Exception {
        var ctx = ctxWithSevenDays();
        String base = "/api/v1/orchestrator/companies/" + ctx.company() + "/accounts/" + ctx.account();
        send("POST", base + "/expected-payments", ctx.owner(), "{\"title\":\"План\",\"direction\":\"OUT\","
                + "\"amount\":\"10.00\",\"dueDate\":\"2026-10-29\",\"categoryCode\":\"SUPPLIERS\","
                + "\"relation\":\"ADDITIONAL\",\"source\":\"MANUAL\"}");
        CLOCK.set(Instant.parse("2026-10-25T09:00:00Z"));
        var scope = access.requireRead(ctx.owner(), ctx.company());

        var history = tx.execute(s -> service.buildCurrent(scope, ctx.account(), LocalDate.parse("2026-10-24"),
                "HISTORY_ONLY", List.of()));
        assertThat(history.snapshot().knownPlans()).isEmpty();
        assertThatThrownBy(() -> service.buildCurrent(scope, ctx.account(), LocalDate.parse("2026-10-25"),
                "WITH_KNOWN_PLANS", List.of())).hasMessageContaining("закрытым");
    }

    @Test
    void storedSnapshotIsImmutableAndNotChangedByLaterData() throws Exception {
        var ctx = ctxWithSevenDays();
        CLOCK.set(Instant.parse("2026-10-25T09:00:00Z"));
        var scope = access.requireWrite(ctx.owner(), ctx.company());
        var stored = tx.execute(s -> service.store(scope, "FORECAST", service.buildCurrent(scope, ctx.account(),
                LocalDate.parse("2026-10-24"), "WITH_KNOWN_PLANS", List.of())));

        String base = "/api/v1/orchestrator/companies/" + ctx.company() + "/accounts/" + ctx.account();
        send("POST", base + "/expected-payments", ctx.owner(), "{\"title\":\"Новый\",\"direction\":\"OUT\","
                + "\"amount\":\"10.00\",\"dueDate\":\"2026-10-29\",\"categoryCode\":\"SUPPLIERS\","
                + "\"relation\":\"ADDITIONAL\",\"source\":\"MANUAL\"}");
        var reread = snapshots.find(scope, stored.id()).orElseThrow();
        // jsonb normalises key order; immutability is about content, which the fingerprint captures.
        assertThat(json.readTree(reread.payload())).isEqualTo(json.readTree(stored.payload()));
        assertThat(json.readTree(reread.payload()).path("knownPlans")).isEmpty();
        assertThat(Fingerprint.of(json.readTree(reread.payload()))).isEqualTo(stored.fingerprint());
        assertThatThrownBy(() -> jdbc.sql("update snapshots set as_of = as_of where id = :id")
                .param("id", stored.id()).update()).hasMessageContaining("immutable");

        var newer = tx.execute(s -> service.buildCurrent(scope, ctx.account(), LocalDate.parse("2026-10-24"),
                "WITH_KNOWN_PLANS", List.of()));
        assertThat(newer.snapshot().inputDataVersion()).isGreaterThan(stored.inputVersion());
        assertThat(newer.snapshot().fingerprint()).isNotEqualTo(stored.fingerprint());
    }

    @Test
    void factsAfterAsOfAreNeverInCurrentSnapshotAndMissingStartBalanceIsRejected() throws Exception {
        var ctx = ctxWithSevenDays();
        insertPayment(ctx, "2026-10-26", "999.00", Direction.IN, Instant.parse("2026-10-26T12:00:00Z"));
        CLOCK.set(Instant.parse("2026-10-27T09:00:00Z"));
        var scope = access.requireRead(ctx.owner(), ctx.company());

        var built = tx.execute(s -> service.buildCurrent(scope, ctx.account(), LocalDate.parse("2026-10-24"),
                "WITH_KNOWN_PLANS", List.of()));
        assertThat(built.snapshot().payments()).allMatch(p -> p.date().compareTo("2026-10-24") <= 0);
        assertThat(built.snapshot().dailyHistory()).allMatch(d -> d.date().compareTo("2026-10-24") <= 0);

        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Без остатка");
        UUID account = createAccount(owner, company, null);
        assertThatThrownBy(() -> service.buildCurrent(access.requireRead(owner, company), account,
                LocalDate.parse("2026-10-24"), "WITH_KNOWN_PLANS", List.of())).isInstanceOf(SnapshotException.class);
    }

    /** Seven complete days 2026-10-18..24, IN 10000 / OUT 8000 per day, EOD 88000..100000 as in calculate.json. */
    private Ctx ctxWithSevenDays() throws Exception {
        CLOCK.set(Instant.parse("2026-10-17T09:00:00Z"));
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Snapshot");
        UUID account = createAccount(owner, company, null);
        var ctx = new Ctx(owner, company, account);
        Instant known = Instant.parse("2026-10-17T10:00:00Z");
        BigDecimal eod = new BigDecimal("86000.00");
        var scope = access.requireWrite(owner, company);
        for (int i = 0; i < 7; i++) {
            String date = LocalDate.parse("2026-10-18").plusDays(i).toString();
            insertPayment(ctx, date, "10000.00", Direction.IN, known);
            insertPayment(ctx, date, "8000.00", Direction.OUT, known);
            eod = eod.add(new BigDecimal("2000.00"));
            ledger.insertAnchor(scope, new ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor(
                    UUID.randomUUID(), account, LocalDate.parse(date), eod,
                    ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.AnchorSource.STATEMENT, "Выписка", known,
                    null, null), null);
        }
        ledger.insertCompleteness(scope, new ru.cashflow.orchestrator.features.ledger.model.LedgerTypes
                .CompletenessRange(UUID.randomUUID(), account, LocalDate.parse("2026-10-18"),
                LocalDate.parse("2026-10-24"),
                ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness.CONFIRMED_COMPLETE,
                "Синтетический полный диапазон", known, null));
        CLOCK.set(Instant.parse("2026-10-20T09:00:00Z"));
        return ctx;
    }

    private void insertPayment(Ctx ctx, String date, String amount, Direction direction, Instant known) {
        String key = direction == Direction.IN ? "client-a" : "supplier-a";
        String category = direction == Direction.IN ? "SALES" : "SUPPLIERS";
        ledger.insertPayment(new Payment(UUID.randomUUID(), ctx.company(), ctx.account(), null, null, null,
                LocalDate.parse(date), new BigDecimal(amount), direction, key, null, null, null, category,
                CategorySource.RULE, "R1", null, 0, known, known));
    }

    private SnapshotService.Built build(Ctx ctx) {
        var scope = access.requireRead(ctx.owner(), ctx.company());
        return tx.execute(s -> service.buildCurrent(scope, ctx.account(), LocalDate.parse("2026-10-24"),
                "WITH_KNOWN_PLANS", List.of()));
    }

    private JsonNode fixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/forecast-examples/calculate.json")) {
            return json.readTree(in);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }

    private record Ctx(UUID owner, UUID company, UUID account) {
    }
}
