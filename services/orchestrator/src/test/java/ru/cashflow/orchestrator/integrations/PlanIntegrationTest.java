package ru.cashflow.orchestrator.integrations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class PlanIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    LedgerRepository ledger;

    @Test
    void planResponseMatchesForecastExpectedPaymentShapeAndIncrementsDataVersion() throws Exception {
        var ctx = ctx();
        var created = createPlan(ctx, plan("OUT", "160000.00", "ADDITIONAL", null, null));

        assertThat(created.status()).isEqualTo(201);
        var body = created.body();
        assertThat(body.path("status").asText()).isEqualTo("OPEN");
        assertThat(body.path("amount").asText()).isEqualTo("160000.00");
        assertThat(body.path("settledAmount").asText()).isEqualTo("0.00");
        assertThat(body.path("remainingAmount").asText()).isEqualTo("160000.00");
        assertThat(body.has("recurringPatternId")).isTrue();
        assertThat(body.path("recurringPatternId").isNull()).isTrue();
        assertThat(body.path("sourceDocument").isNull()).isTrue();
        assertThat(body.has("overdue")).isTrue();
        assertThat(dataVersion(ctx)).isEqualTo(1);
        assertThat(revisionCount()).isEqualTo(1);
    }

    @Test
    void relationRequiresExactlyOneMatchingLink() throws Exception {
        var ctx = ctx();
        assertThat(createPlan(ctx, plan("OUT", "1.00", "REPLACES_RECURRING", null, null)).status()).isEqualTo(422);
        assertThat(createPlan(ctx, plan("OUT", "1.00", "REPLACES_BASELINE", null, null)).status()).isEqualTo(422);
        assertThat(createPlan(ctx, plan("OUT", "1.00", "ADDITIONAL", UUID.randomUUID().toString(), null)).status())
                .isEqualTo(422);
        assertThat(createPlan(ctx, plan("OUT", "1.00", "REPLACES_BASELINE", null, "inn:0012345678")).status())
                .isEqualTo(201);
        assertThat(createPlan(ctx, plan("OUT", "0.00", "ADDITIONAL", null, null)).status()).isEqualTo(422);
    }

    @Test
    void allocationCannotExceedPlanOrPaymentAndPartialSettlementReducesRemaining() throws Exception {
        var ctx = ctx();
        String planId = createPlan(ctx, plan("IN", "1000.00", "ADDITIONAL", null, null)).body().path("id").asText();
        UUID small = payment(ctx, Direction.IN, "400.00");
        UUID large = payment(ctx, Direction.IN, "900.00");

        assertThat(allocate(ctx, planId, small, "500.00").status()).isEqualTo(422);
        assertThat(allocate(ctx, planId, small, "400.00").status()).isEqualTo(201);
        var partial = get(ctx, planId);
        assertThat(partial.path("status").asText()).isEqualTo("PARTIALLY_SETTLED");
        assertThat(partial.path("settledAmount").asText()).isEqualTo("400.00");
        assertThat(partial.path("remainingAmount").asText()).isEqualTo("600.00");
        assertThat(allocate(ctx, planId, large, "700.00").status()).isEqualTo(422);
        assertThat(allocate(ctx, planId, large, "600.00").status()).isEqualTo(201);
        assertThat(get(ctx, planId).path("status").asText()).isEqualTo("SETTLED");
        assertThat(allocate(ctx, planId, small, "1.00").status()).isIn(409, 422);
    }

    @Test
    void directionMismatchAndCancelledPlanAreRejected() throws Exception {
        var ctx = ctx();
        String planId = createPlan(ctx, plan("IN", "100.00", "ADDITIONAL", null, null)).body().path("id").asText();
        UUID outgoing = payment(ctx, Direction.OUT, "100.00");
        assertThat(allocate(ctx, planId, outgoing, "50.00").status()).isEqualTo(422);

        long version = get(ctx, planId).path("version").asLong();
        assertThat(send("POST", base(ctx) + "/expected-payments/" + planId + "/cancel", ctx.owner(), null,
                "If-Match", "\"" + version + "\"").body().path("remainingAmount").asText()).isEqualTo("0.00");
        UUID incoming = payment(ctx, Direction.IN, "100.00");
        assertThat(allocate(ctx, planId, incoming, "50.00").status()).isEqualTo(409);
    }

    @Test
    void reversalKeepsHistoryAndRestoresRemaining() throws Exception {
        var ctx = ctx();
        String planId = createPlan(ctx, plan("IN", "100.00", "ADDITIONAL", null, null)).body().path("id").asText();
        UUID pay = payment(ctx, Direction.IN, "100.00");
        String allocation = allocate(ctx, planId, pay, "100.00").body().path("id").asText();

        assertThat(send("POST", base(ctx) + "/allocations/" + allocation + "/reverse", ctx.owner(), null).status())
                .isEqualTo(204);
        assertThat(get(ctx, planId).path("status").asText()).isEqualTo("OPEN");
        assertThat(get(ctx, planId).path("remainingAmount").asText()).isEqualTo("100.00");
        var history = send("GET", base(ctx) + "/expected-payments/" + planId + "/allocations", ctx.owner(), null)
                .body().path("items");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("reversedAt").isNull()).isFalse();
        assertThat(allocate(ctx, planId, pay, "100.00").status()).isEqualTo(201);
    }

    @Test
    void concurrentAllocationsNeverOverAllocateOnePayment() throws Exception {
        var ctx = ctx();
        String planA = createPlan(ctx, plan("IN", "100.00", "ADDITIONAL", null, null)).body().path("id").asText();
        String planB = createPlan(ctx, plan("IN", "100.00", "ADDITIONAL", null, null)).body().path("id").asText();
        UUID pay = payment(ctx, Direction.IN, "100.00");

        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> a = () -> allocate(ctx, planA, pay, "100.00").status();
            Callable<Integer> b = () -> allocate(ctx, planB, pay, "100.00").status();
            var results = pool.invokeAll(java.util.List.of(a, b));
            int created = 0;
            for (var r : results) {
                created += r.get() == 201 ? 1 : 0;
            }
            assertThat(created).isEqualTo(1);
        }
        assertThat(jdbc.sql("select coalesce(sum(amount),0) from allocations where payment_id = :p and reversed_at is null")
                .param("p", pay).query(BigDecimal.class).single()).isEqualByComparingTo("100.00");
    }

    @Test
    void staleVersionViewerAndForeignUserAreRejected() throws Exception {
        var ctx = ctx();
        UUID viewer = UUID.randomUUID();
        addMember(ctx.owner(), ctx.company(), viewer, "VIEWER");
        String planId = createPlan(ctx, plan("OUT", "10.00", "ADDITIONAL", null, null)).body().path("id").asText();

        assertThat(send("PUT", base(ctx) + "/expected-payments/" + planId, ctx.owner(),
                plan("OUT", "20.00", "ADDITIONAL", null, null), "If-Match", "\"5\"").status()).isEqualTo(409);
        assertThat(send("POST", base(ctx) + "/expected-payments", viewer, plan("OUT", "1.00", "ADDITIONAL", null, null))
                .status()).isEqualTo(403);
        assertThat(send("GET", base(ctx) + "/expected-payments/" + planId, UUID.randomUUID(), null).status())
                .isEqualTo(404);
        var updated = send("PUT", base(ctx) + "/expected-payments/" + planId, ctx.owner(),
                plan("OUT", "20.00", "ADDITIONAL", null, null), "If-Match", "\"0\"");
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().path("amount").asText()).isEqualTo("20.00");
        assertThat(revisionCount()).isEqualTo(2);
    }

    private Ctx ctx() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Планы");
        return new Ctx(owner, company, createAccount(owner, company, null));
    }

    private Response createPlan(Ctx ctx, String body) throws Exception {
        return send("POST", base(ctx) + "/expected-payments", ctx.owner(), body);
    }

    private com.fasterxml.jackson.databind.JsonNode get(Ctx ctx, String planId) throws Exception {
        return send("GET", base(ctx) + "/expected-payments/" + planId, ctx.owner(), null).body();
    }

    private Response allocate(Ctx ctx, String planId, UUID payment, String amount) throws Exception {
        return send("POST", base(ctx) + "/expected-payments/" + planId + "/allocations", ctx.owner(),
                "{\"paymentId\":\"" + payment + "\",\"amount\":\"" + amount + "\"}");
    }

    private UUID payment(Ctx ctx, Direction direction, String amount) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        ledger.insertPayment(new Payment(id, ctx.company(), ctx.account(), null, null, null,
                LocalDate.parse("2026-09-15"), new BigDecimal(amount), direction, null, null, null, null, "OTHER",
                CategorySource.OTHER, null, null, 0, now, now));
        return id;
    }

    private long dataVersion(Ctx ctx) throws Exception {
        return send("GET", "/api/v1/orchestrator/companies/" + ctx.company(), ctx.owner(), null).body()
                .path("dataVersion").asLong();
    }

    private long revisionCount() {
        return jdbc.sql("select count(*) from expected_payment_revisions").query(Long.class).single();
    }

    private static String plan(String direction, String amount, String relation, String patternId, String baseline) {
        return "{\"title\":\"Оплата\",\"direction\":\"" + direction + "\",\"amount\":\"" + amount + "\","
                + "\"dueDate\":\"2026-11-15\",\"categoryCode\":\"SUPPLIERS\",\"counterpartyKey\":\"inn:0012345678\","
                + "\"relation\":\"" + relation + "\",\"recurringPatternId\":"
                + (patternId == null ? "null" : "\"" + patternId + "\"") + ",\"baselineCounterpartyKey\":"
                + (baseline == null ? "null" : "\"" + baseline + "\"") + ",\"source\":\"MANUAL\"}";
    }

    private static String base(Ctx ctx) {
        return "/api/v1/orchestrator/companies/" + ctx.company() + "/accounts/" + ctx.account();
    }

    private record Ctx(UUID owner, UUID company, UUID account) {
    }
}
