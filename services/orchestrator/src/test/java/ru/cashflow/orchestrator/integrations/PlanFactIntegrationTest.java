package ru.cashflow.orchestrator.integrations;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class PlanFactIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    LedgerRepository ledger;

    @ParameterizedTest
    @EnumSource(Direction.class)
    void voidedManualFactsAreExcludedFromTotalsAndDetails(Direction direction) throws Exception {
        var ctx = ctx();
        LocalDate date = LocalDate.now().minusDays(4);
        String plan = createPlan(ctx, direction.name(), "100.00", date);
        UUID manual = manualPayment(ctx, direction, date);
        assertThat(allocate(ctx, plan, manual, "60.00").status()).isEqualTo(201);

        var voided = send("POST", companyBase(ctx) + "/payments/" + manual + "/void", ctx.owner(),
                "{\"reason\":\"Ошибочный факт\"}", "If-Match", "\"1\"");
        assertThat(voided.status()).as(voided.raw()).isEqualTo(200);

        var response = planFact(ctx, date, date, "DAY");
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        String suffix = direction == Direction.IN ? "In" : "Out";
        var totals = response.body().path("totals");
        assertThat(totals.path("actual" + suffix).asText()).isEqualTo("0.00");
        assertThat(totals.path("unplanned" + suffix).asText()).isEqualTo("0.00");
        assertThat(totals.path("settled" + suffix).asText()).isEqualTo("0.00");
        assertThat(totals.path("remaining" + suffix).asText()).isEqualTo("100.00");
        assertThat(response.body().path("periods").get(0).path("unplannedFacts")).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    void sameDuplicateCountsOnlyTheBankReplacementAndPreservesSettlement(Direction direction) throws Exception {
        var ctx = ctx();
        LocalDate date = LocalDate.now().minusDays(4);
        String plan = createPlan(ctx, direction.name(), "100.00", date);
        UUID manual = manualPayment(ctx, direction, date);
        assertThat(allocate(ctx, plan, manual, "60.00").status()).isEqualTo(201);
        UUID bank = payment(ctx, direction, "100.00", date);

        var resolved = send("POST", companyBase(ctx) + "/payments/" + manual + "/duplicate-resolutions", ctx.owner(),
                "{\"importedPaymentId\":\"" + bank + "\",\"importedPaymentVersion\":1,"
                        + "\"decision\":\"SAME\",\"reason\":\"Пришла выписка\"}", "If-Match", "\"1\"");
        assertThat(resolved.status()).as(resolved.raw()).isEqualTo(200);

        var response = planFact(ctx, date, date, "DAY");
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        String suffix = direction == Direction.IN ? "In" : "Out";
        var totals = response.body().path("totals");
        assertThat(totals.path("actual" + suffix).asText()).isEqualTo("100.00");
        assertThat(totals.path("unplanned" + suffix).asText()).isEqualTo("40.00");
        assertThat(totals.path("settled" + suffix).asText()).isEqualTo("60.00");
        assertThat(totals.path("remaining" + suffix).asText()).isEqualTo("40.00");
        var facts = response.body().path("periods").get(0).path("unplannedFacts");
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).path("id").asText()).isEqualTo(bank.toString());
        assertThat(facts.get(0).path("unallocatedAmount").asText()).isEqualTo("40.00");
    }

    @Test
    void dailyPlanFactSeparatesCashMovementFromPlanCoverage() throws Exception {
        var ctx = ctx();
        LocalDate first = LocalDate.now().minusDays(10);
        LocalDate second = first.plusDays(1);
        String planOut = createPlan(ctx, "OUT", "1000.00", first);
        createPlan(ctx, "IN", "500.00", second);
        UUID paymentOut = payment(ctx, Direction.OUT, "850.00", first);
        payment(ctx, Direction.IN, "500.00", second);
        assertThat(allocate(ctx, planOut, paymentOut, "800.00").status()).isEqualTo(201);

        var response = planFact(ctx, first, second, "DAY");
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        assertThat(response.body().path("periods")).hasSize(2);
        var firstDay = response.body().path("periods").get(0);
        assertThat(firstDay.path("plannedOut").asText()).isEqualTo("1000.00");
        assertThat(firstDay.path("actualOut").asText()).isEqualTo("850.00");
        assertThat(firstDay.path("varianceOut").asText()).isEqualTo("-150.00");
        assertThat(firstDay.path("unplannedOut").asText()).isEqualTo("50.00");
        assertThat(firstDay.path("overduePlans")).hasSize(1);
        assertThat(firstDay.path("overduePlans").get(0).path("remainingAmount").asText()).isEqualTo("200.00");
        var secondDay = response.body().path("periods").get(1);
        assertThat(secondDay.path("plannedIn").asText()).isEqualTo("500.00");
        assertThat(secondDay.path("actualIn").asText()).isEqualTo("500.00");
        assertThat(secondDay.path("varianceIn").asText()).isEqualTo("0.00");
        assertThat(response.body().path("totals").path("remainingOut").asText()).isEqualTo("200.00");
    }

    @Test
    void weeklyBucketsCoverMondayToSundayAndEmptyPeriodsStayZero() throws Exception {
        var ctx = ctx();
        LocalDate monday = LocalDate.now().minusWeeks(2).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        createPlan(ctx, "OUT", "300.00", monday.plusDays(2));
        payment(ctx, Direction.OUT, "100.00", monday.plusDays(3));

        var response = planFact(ctx, monday, sunday.plusWeeks(1), "WEEK");
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        assertThat(response.body().path("periods")).hasSize(2);
        assertThat(response.body().path("periods").get(0).path("periodStart").asText()).isEqualTo(monday.toString());
        assertThat(response.body().path("periods").get(0).path("periodEnd").asText()).isEqualTo(sunday.toString());
        assertThat(response.body().path("periods").get(1).path("plannedOut").asText()).isEqualTo("0.00");
        assertThat(response.body().path("periods").get(1).path("actualOut").asText()).isEqualTo("0.00");
    }

    @Test
    void cancelledPlansAreExcludedAndReversalMakesTheFactUnplanned() throws Exception {
        var ctx = ctx();
        LocalDate date = LocalDate.now().minusDays(4);
        String plan = createPlan(ctx, "OUT", "100.00", date);
        UUID pay = payment(ctx, Direction.OUT, "100.00", date);
        String allocation = allocate(ctx, plan, pay, "100.00").body().path("id").asText();

        assertThat(planFact(ctx, date, date, "DAY").body().path("periods").get(0).path("unplannedOut").asText())
                .isEqualTo("0.00");
        assertThat(send("POST", base(ctx) + "/allocations/" + allocation + "/reverse", ctx.owner(), null).status())
                .isEqualTo(204);
        var afterReversal = planFact(ctx, date, date, "DAY").body().path("periods").get(0);
        assertThat(afterReversal.path("unplannedOut").asText()).isEqualTo("100.00");

        long version = send("GET", base(ctx) + "/expected-payments/" + plan, ctx.owner(), null).body()
                .path("version").asLong();
        send("POST", base(ctx) + "/expected-payments/" + plan + "/cancel", ctx.owner(), null,
                "If-Match", "\"" + version + "\"");
        assertThat(planFact(ctx, date, date, "DAY").body().path("periods").get(0).path("plannedOut").asText())
                .isEqualTo("0.00");
    }

    @Test
    void readRolesTenantAndRangeAreChecked() throws Exception {
        var ctx = ctx();
        UUID viewer = UUID.randomUUID();
        addMember(ctx.owner(), ctx.company(), viewer, "VIEWER");
        LocalDate date = LocalDate.now().minusDays(2);

        assertThat(planFact(ctx, date, date, "DAY", viewer, ctx.account()).status()).isEqualTo(200);
        assertThat(planFact(ctx, date, date, "DAY", UUID.randomUUID(), ctx.account()).status()).isEqualTo(404);
        assertThat(planFact(ctx, date, date, "DAY", ctx.owner(), UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(planFact(ctx, date.plusDays(1), date, "DAY").status()).isEqualTo(422);
        assertThat(planFact(ctx, date.minusDays(400), date, "DAY").status()).isEqualTo(422);
        assertThat(planFact(ctx, date.minusDays(365), date, "DAY").status()).isEqualTo(200);
        assertThat(planFact(ctx, date.minusDays(366), date, "DAY").status()).isEqualTo(422);
    }

    private Ctx ctx() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "План-факт");
        return new Ctx(owner, company, createAccount(owner, company, null));
    }

    private UUID manualPayment(Ctx ctx, Direction direction, LocalDate date) throws Exception {
        var response = send("POST", companyBase(ctx) + "/payments", ctx.owner(),
                "{\"accountId\":\"" + ctx.account() + "\",\"operationDate\":\"" + date
                        + "\",\"amount\":\"100.00\",\"direction\":\"" + direction + "\",\"categoryCode\":\"OTHER\"}",
                "Idempotency-Key", "plan-fact-" + UUID.randomUUID());
        assertThat(response.status()).as(response.raw()).isEqualTo(201);
        return UUID.fromString(response.body().path("id").asText());
    }

    private static String companyBase(Ctx ctx) {
        return "/api/v1/orchestrator/companies/" + ctx.company();
    }

    private String createPlan(Ctx ctx, String direction, String amount, LocalDate dueDate) throws Exception {
        var response = send("POST", base(ctx) + "/expected-payments", ctx.owner(),
                "{\"title\":\"План\",\"direction\":\"" + direction + "\",\"amount\":\"" + amount
                        + "\",\"dueDate\":\"" + dueDate + "\",\"categoryCode\":\"SUPPLIERS\","
                        + "\"relation\":\"ADDITIONAL\",\"source\":\"MANUAL\"}");
        assertThat(response.status()).as(response.raw()).isEqualTo(201);
        return response.body().path("id").asText();
    }

    private UUID payment(Ctx ctx, Direction direction, String amount, LocalDate date) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        ledger.insertPayment(new Payment(id, ctx.company(), ctx.account(), null, null, null, date,
                new BigDecimal(amount), direction, null, null, null, null, "OTHER", CategorySource.OTHER, null,
                null, 0, now, now));
        return id;
    }

    private Response allocate(Ctx ctx, String planId, UUID paymentId, String amount) throws Exception {
        return send("POST", base(ctx) + "/expected-payments/" + planId + "/allocations", ctx.owner(),
                "{\"paymentId\":\"" + paymentId + "\",\"amount\":\"" + amount + "\"}");
    }

    private Response planFact(Ctx ctx, LocalDate from, LocalDate to, String groupBy) throws Exception {
        return planFact(ctx, from, to, groupBy, ctx.owner(), ctx.account());
    }

    private Response planFact(Ctx ctx, LocalDate from, LocalDate to, String groupBy, UUID user, UUID account)
            throws Exception {
        return send("GET", "/api/v1/orchestrator/companies/" + ctx.company() + "/plan-fact?accountId=" + account
                + "&fromDate=" + from + "&toDate=" + to + "&groupBy=" + groupBy, user, null);
    }

    private static String base(Ctx ctx) {
        return "/api/v1/orchestrator/companies/" + ctx.company() + "/accounts/" + ctx.account();
    }

    private record Ctx(UUID owner, UUID company, UUID account) {
    }
}
