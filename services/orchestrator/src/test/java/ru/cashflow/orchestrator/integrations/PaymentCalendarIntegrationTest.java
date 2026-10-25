package ru.cashflow.orchestrator.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.BusinessDayRule;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Frequency;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.PatternStatus;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.RecurringPattern;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Schedule;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.StatusSource;
import ru.cashflow.orchestrator.features.recurring.repository.RecurringRepository;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

@Import(PaymentCalendarIntegrationTest.ClockConfig.class)
class PaymentCalendarIntegrationTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-02-27T22:00:00Z");

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, java.time.ZoneOffset.UTC);
        }
    }

    @Autowired
    LedgerRepository ledger;
    @Autowired
    RecurringRepository recurring;

    @Test
    void groupsExactMoneyAndSettlementsWithoutMovingOverduePlans() throws Exception {
        var ctx = context();
        UUID partial = plan(ctx, "2026-02-27", "100.10", "OUT", null);
        UUID paid = plan(ctx, "2026-02-28", "50.01", "OUT", null);
        UUID cancelled = plan(ctx, "2026-03-01", "300.00", "OUT", null);
        plan(ctx, "2026-02-28", "999.00", "IN", null);
        plan(ctx, "2026-02-26", "20.02", "OUT", null);
        UUID allocation = allocate(ctx, partial, "40.03");
        allocate(ctx, paid, "50.01");
        assertThat(send("POST", base(ctx) + "/expected-payments/" + cancelled + "/cancel", ctx.owner(), null,
                "If-Match", "\"0\"").status()).isEqualTo(200);
        long version = version(ctx);

        var response = calendar(ctx, "2026-02-27", "2026-03-01");
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        var body = response.body();
        assertThat(body.path("today").asText()).isEqualTo("2026-02-28");
        assertThat(body.path("timezone").asText()).isEqualTo("Europe/Moscow");
        assertThat(body.path("currency").asText()).isEqualTo("RUB");
        assertThat(body.path("dataVersion").asLong()).isEqualTo(version);
        assertThat(body.path("totals").path("amount").asText()).isEqualTo("150.11");
        assertThat(body.path("totals").path("settledAmount").asText()).isEqualTo("90.04");
        assertThat(body.path("totals").path("remainingAmount").asText()).isEqualTo("60.07");
        assertThat(body.path("totals").path("overdueAmount").asText()).isEqualTo("60.07");
        assertThat(body.path("days")).hasSize(3);
        var item = body.path("days").get(0).path("items").get(0);
        assertThat(item.path("status").asText()).isEqualTo("PARTIALLY_SETTLED");
        assertThat(item.path("overdue").asBoolean()).isTrue();
        assertThat(item.path("dueDate").asText()).isEqualTo("2026-02-27");
        assertThat(body.path("days").get(1).path("items").get(0).path("status").asText()).isEqualTo("SETTLED");
        assertThat(body.path("days").get(2).path("items")).isEmpty();
        assertThat(body.path("overdueBeforeRange")).hasSize(1);
        assertThat(body.path("overdueBeforeRangeAmount").asText()).isEqualTo("20.02");
        assertThat(version(ctx)).isEqualTo(version);

        assertThat(send("POST", base(ctx) + "/allocations/" + allocation + "/reverse", ctx.owner(), null).status())
                .isEqualTo(204);
        assertThat(calendar(ctx, "2026-02-27", "2026-03-01").body().path("totals").path("remainingAmount").asText())
                .isEqualTo("100.10");
    }

    @Test
    void expandsConfirmedOutflowsAcrossMonthBoundaryAndDoesNotInventHistoricDebt() throws Exception {
        var ctx = context();
        UUID pattern = pattern(ctx, "MONTHLY", 31, PatternStatus.CONFIRMED, Direction.OUT, "100.01");
        pattern(ctx, "WEEKLY", 6, PatternStatus.DETECTED, Direction.OUT, "500.00");
        pattern(ctx, "WEEKLY", 7, PatternStatus.EXCLUDED, Direction.OUT, "600.00");
        pattern(ctx, "MONTHLY", 28, PatternStatus.CONFIRMED, Direction.IN, "700.00");

        var body = calendar(ctx, "2026-03-02", "2026-03-02").body();
        var items = body.path("days").get(0).path("items");
        assertThat(items).hasSize(1);
        var item = items.get(0);
        assertThat(item.path("kind").asText()).isEqualTo("RECURRING");
        assertThat(item.path("recurringPatternId").asText()).isEqualTo(pattern.toString());
        assertThat(item.path("nominalDate").asText()).isEqualTo("2026-02-28");
        assertThat(item.path("dueDate").asText()).isEqualTo("2026-03-02");
        assertThat(item.path("status").asText()).isEqualTo("SCHEDULED");
        assertThat(item.path("settledAmount").isNull()).isTrue();
        assertThat(body.path("totals").path("remainingAmount").asText()).isEqualTo("100.01");
        assertThat(body.path("warnings")).hasSize(2);
        assertThat(calendar(ctx, "2026-03-02", "2026-03-02").body()).isEqualTo(body);

        var past = calendar(ctx, "2026-01-01", "2026-02-27").body();
        assertThat(past.path("totals").path("remainingAmount").asText()).isEqualTo("0.00");
        assertThat(past.path("overdueBeforeRange")).isEmpty();
    }

    @Test
    void replacementOnNominalDateSuppressesShiftedOccurrenceEvenWhenSettled() throws Exception {
        var ctx = context();
        UUID pattern = pattern(ctx, "WEEKLY", 6, PatternStatus.CONFIRMED, Direction.OUT, "120.00");
        UUID plan = plan(ctx, "2026-02-28", "100.00", "OUT", pattern);
        UUID allocation = allocate(ctx, plan, "40.00");

        var partial = calendar(ctx, "2026-02-28", "2026-03-02").body();
        assertThat(partial.path("days").get(0).path("items")).hasSize(1);
        assertThat(partial.path("days").get(2).path("items")).isEmpty();
        assertThat(partial.path("totals").path("remainingAmount").asText()).isEqualTo("60.00");
        allocate(ctx, plan, "60.00");
        var settled = calendar(ctx, "2026-03-02", "2026-03-02").body();
        assertThat(settled.path("days").get(0).path("items")).isEmpty();
        assertThat(settled.path("overdueBeforeRange")).isEmpty();

        assertThat(send("POST", base(ctx) + "/allocations/" + allocation + "/reverse", ctx.owner(), null).status())
                .isEqualTo(204);
        var reversed = calendar(ctx, "2026-03-02", "2026-03-02").body();
        assertThat(reversed.path("days").get(0).path("items")).isEmpty();
        assertThat(reversed.path("overdueBeforeRange")).isEmpty(); // Due today, not overdue.
    }

    @Test
    void replacementOnShiftedDateAndCancellationRespectOccurrenceIdentity() throws Exception {
        var ctx = context();
        UUID pattern = pattern(ctx, "WEEKLY", 7, PatternStatus.CONFIRMED, Direction.OUT, "120.00");
        UUID plan = plan(ctx, "2026-03-02", "90.00", "OUT", pattern);
        var replaced = calendar(ctx, "2026-03-02", "2026-03-09").body();
        assertThat(replaced.path("days").get(0).path("items")).hasSize(1);
        assertThat(replaced.path("days").get(0).path("items").get(0).path("kind").asText()).isEqualTo("PLANNED");
        assertThat(replaced.path("days").get(7).path("items")).hasSize(1);
        assertThat(replaced.path("totals").path("remainingAmount").asText()).isEqualTo("210.00");

        assertThat(send("POST", base(ctx) + "/expected-payments/" + plan + "/cancel", ctx.owner(), null,
                "If-Match", "\"0\"").status()).isEqualTo(200);
        var cancelled = calendar(ctx, "2026-03-02", "2026-03-02").body();
        assertThat(cancelled.path("days").get(0).path("items")).hasSize(1);
        assertThat(cancelled.path("totals").path("remainingAmount").asText()).isEqualTo("120.00");
    }

    @Test
    void validatesRangeAndEnforcesReadAccessAndAccountOwnership() throws Exception {
        var ctx = context();
        UUID viewer = UUID.randomUUID();
        UUID accountant = UUID.randomUUID();
        addMember(ctx.owner(), ctx.company(), viewer, "VIEWER");
        addMember(ctx.owner(), ctx.company(), accountant, "ACCOUNTANT");
        String path = base(ctx) + "/payment-calendar?fromDate=2026-02-28&toDate=2026-02-28";
        assertThat(send("GET", path, viewer, null).status()).isEqualTo(200);
        assertThat(send("GET", path, accountant, null).status()).isEqualTo(200);
        assertThat(send("GET", path, null, null).status()).isEqualTo(401);
        assertThat(send("GET", path, UUID.randomUUID(), null).status()).isEqualTo(404);
        var foreign = context();
        assertThat(send("GET", path.replace(ctx.account().toString(), foreign.account().toString()), ctx.owner(), null)
                .status()).isEqualTo(404);
        assertThat(calendar(ctx, "2026-03-01", "2026-02-28").status()).isEqualTo(422);
        assertThat(calendar(ctx, "2026-01-01", "2027-01-02").status()).isEqualTo(422);
        assertThat(calendar(ctx, "2026-01-01", "2027-01-01").body().path("days")).hasSize(366);
        assertThat(send("GET", base(ctx) + "/payment-calendar", ctx.owner(), null).status()).isEqualTo(400);
        assertThat(calendar(ctx, "invalid", "2026-02-28").status()).isEqualTo(400);
        assertThat(send("POST", base(ctx) + "/payment-calendar", ctx.owner(), null).status()).isEqualTo(405);
    }

    @Test
    void preservesTaxAndExternalInvoiceProvenanceWithoutAddingReferenceTaxDeadlines() throws Exception {
        var ctx = context();
        for (String source : java.util.List.of("TAX", "INVOICE", "CONTRACT")) {
            var created = send("POST", base(ctx) + "/expected-payments", ctx.owner(), """
                    {"title":"Платёж","direction":"OUT","amount":"10.11","dueDate":"2026-02-28",
                     "categoryCode":"OTHER","relation":"ADDITIONAL","source":"%s",
                     "sourceDocument":{"externalSystem":"1C","externalId":"%s","documentNumber":"42",
                     "issuedDate":"2026-02-01"}}
                    """.formatted(source, source));
            assertThat(created.status()).as(created.raw()).isEqualTo(201);
        }
        var body = calendar(ctx, "2026-02-28", "2026-02-28").body();
        assertThat(body.path("days").get(0).path("items")).hasSize(3);
        assertThat(body.path("totals").path("remainingAmount").asText()).isEqualTo("30.33");
        for (var item : body.path("days").get(0).path("items")) {
            assertThat(item.path("sourceDocument").path("externalSystem").asText()).isEqualTo("1C");
            assertThat(item.path("sourceDocument").path("externalId").asText()).isEqualTo(item.path("source").asText());
            assertThat(item.path("sourceDocument").path("issuedDate").asText()).isEqualTo("2026-02-01");
            assertThat(item.path("overdue").asBoolean()).isFalse();
        }
    }

    private Ctx context() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Payment calendar");
        return new Ctx(owner, company, createAccount(owner, company, null));
    }

    private Response calendar(Ctx ctx, String from, String to) throws Exception {
        return send("GET", base(ctx) + "/payment-calendar?fromDate=" + from + "&toDate=" + to, ctx.owner(), null);
    }

    private UUID plan(Ctx ctx, String date, String amount, String direction, UUID pattern) throws Exception {
        String relation = pattern == null ? "ADDITIONAL" : "REPLACES_RECURRING";
        var response = send("POST", base(ctx) + "/expected-payments", ctx.owner(), """
                {"title":"Обязательный платёж","direction":"%s","amount":"%s","dueDate":"%s",
                 "categoryCode":"OTHER","relation":"%s","recurringPatternId":%s,"source":"MANUAL"}
                """.formatted(direction, amount, date, relation, pattern == null ? "null" : "\"" + pattern + "\""));
        assertThat(response.status()).as(response.raw()).isEqualTo(201);
        return UUID.fromString(response.body().path("id").asText());
    }

    private UUID allocate(Ctx ctx, UUID plan, String amount) throws Exception {
        UUID payment = UUID.randomUUID();
        ledger.insertPayment(new Payment(payment, ctx.company(), ctx.account(), null, null, null,
                LocalDate.parse("2026-02-26"), new BigDecimal(amount), Direction.OUT, null, null, null, null,
                "OTHER", CategorySource.OTHER, null, null, 0, NOW, NOW));
        var response = send("POST", base(ctx) + "/expected-payments/" + plan + "/allocations", ctx.owner(),
                "{\"paymentId\":\"" + payment + "\",\"amount\":\"" + amount + "\"}");
        assertThat(response.status()).as(response.raw()).isEqualTo(201);
        return UUID.fromString(response.body().path("id").asText());
    }

    private UUID pattern(Ctx ctx, String frequency, int day, PatternStatus status, Direction direction, String amount) {
        UUID id = UUID.randomUUID();
        Frequency freq = Frequency.valueOf(frequency);
        var schedule = new Schedule(freq, freq == Frequency.WEEKLY ? day : null,
                freq == Frequency.MONTHLY ? day : null, BusinessDayRule.FOLLOWING);
        recurring.insert(new RecurringPattern(id, ctx.company(), ctx.account(), id.toString(), direction, "OTHER",
                null, status, StatusSource.USER, schedule, new BigDecimal(amount), 3, LocalDate.parse("2025-10-01"),
                LocalDate.parse("2026-01-31"), "TEST", 0, NOW, NOW, 0, false));
        return id;
    }

    private long version(Ctx ctx) {
        return jdbc.sql("select data_version from companies where id = :id").param("id", ctx.company())
                .query(Long.class).single();
    }

    private static String base(Ctx ctx) {
        return "/api/v1/orchestrator/companies/" + ctx.company() + "/accounts/" + ctx.account();
    }

    private record Ctx(UUID owner, UUID company, UUID account) {
    }
}
