package ru.cashflow.orchestrator.integrations;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    LedgerRepository ledger;

    @Test
    void categoryAmountsAndSharesUseEachDirectionsOwnTotal() throws Exception {
        var ctx = context();
        LocalDate date = LocalDate.parse("2026-09-02");
        insert(ctx, date, "10.00", Direction.OUT, "RENT", "supplier", null, "Seller");
        insert(ctx, date, "30.00", Direction.OUT, "RENT", "supplier", null, "Seller");
        insert(ctx, date, "60.00", Direction.OUT, "TAXES", null, null, null);
        insert(ctx, date, "25.00", Direction.IN, "SALES", "buyer", null, "Buyer");
        insert(ctx, date.minusDays(1), "100.00", Direction.OUT, "RENT", null, null, null);

        var response = read(ctx, "categories", date, date, null, ctx.owner(), ctx.account());
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        var body = response.body();
        assertThat(body.path("totals")).hasSize(2);
        assertThat(find(body.path("totals"), "direction", "OUT").path("amount").asText())
                .isEqualTo("100.00");
        assertThat(find(body.path("totals"), "direction", "OUT").path("paymentCount").asLong())
                .isEqualTo(3);
        assertThat(find(body.path("totals"), "direction", "IN").path("amount").asText())
                .isEqualTo("25.00");
        var rent = find(body.path("items"), "categoryCode", "RENT");
        assertThat(rent.path("amount").asText()).isEqualTo("40.00");
        assertThat(rent.path("paymentCount").asLong()).isEqualTo(2);
        assertThat(rent.path("sharePercent").asText()).isEqualTo("40.00");
        assertThat(find(body.path("items"), "categoryCode", "TAXES").path("sharePercent").asText())
                .isEqualTo("60.00");
        assertThat(find(body.path("items"), "categoryCode", "SALES").path("sharePercent").asText())
                .isEqualTo("100.00");

        var out = read(ctx, "categories", date, date, "OUT", ctx.owner(), ctx.account()).body();
        assertThat(out.path("totals")).hasSize(1);
        assertThat(out.path("items")).hasSize(2);
        assertThat(out.path("direction").asText()).isEqualTo("OUT");
    }

    @Test
    void counterpartiesUseStableKeyAndLatestDisplayNameAndIncludeUnknown() throws Exception {
        var ctx = context();
        LocalDate date = LocalDate.parse("2026-09-02");
        insert(ctx, date, "30.00", Direction.OUT, "RENT", "inn:0012345678", "0012345678", "Old");
        insert(ctx, date.plusDays(1), "20.00", Direction.OUT, "OTHER", "inn:0012345678", "0012345678", "New");
        insert(ctx, date, "30.00", Direction.OUT, "OTHER", "inn:9876543210", "9876543210", "Other");
        insert(ctx, date, "20.00", Direction.OUT, "OTHER", null, null, null);
        insert(ctx, date, "10.00", Direction.IN, "SALES", "inn:0012345678", "0012345678", "Buyer");

        var response = read(ctx, "counterparties", date, date.plusDays(1), null, ctx.owner(), ctx.account());
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        var rows = response.body().path("items");
        assertThat(rows).hasSize(4);
        var seller = findDirection(rows, "inn:0012345678", "OUT");
        assertThat(seller.path("counterpartyName").asText()).isEqualTo("New");
        assertThat(seller.path("counterpartyInn").asText()).isEqualTo("0012345678");
        assertThat(seller.path("amount").asText()).isEqualTo("50.00");
        assertThat(seller.path("paymentCount").asLong()).isEqualTo(2);
        assertThat(seller.path("sharePercent").asText()).isEqualTo("50.00");
        assertThat(findDirection(rows, "inn:9876543210", "OUT").path("sharePercent").asText())
                .isEqualTo("30.00");
        assertThat(findDirection(rows, null, "OUT").path("sharePercent").asText())
                .isEqualTo("20.00");
        assertThat(findDirection(rows, "inn:0012345678", "IN").path("sharePercent").asText())
                .isEqualTo("100.00");
    }

    @Test
    void currentCategoryChangesAndVoidedFactsAreReflected() throws Exception {
        var ctx = context();
        LocalDate date = LocalDate.parse("2026-09-02");
        UUID voided = insert(ctx, date, "100.00", Direction.OUT, "RENT", "a", null, "Voided");
        UUID active = insert(ctx, date, "60.00", Direction.OUT, "TAXES", "b", null, "Active");
        jdbc.sql("insert into payment_voids (payment_id, company_id, account_id, voided_at, actor_id, reason) "
                        + "values (:payment, :company, :account, :at, :actor, :reason)")
                .param("payment", voided).param("company", ctx.company()).param("account", ctx.account())
                .param("at", Timestamp.from(Instant.now())).param("actor", ctx.owner()).param("reason", "test void").update();

        var before = read(ctx, "categories", date, date, "OUT", ctx.owner(), ctx.account()).body();
        assertThat(before.path("items")).hasSize(1);
        assertThat(before.path("items").get(0).path("categoryCode").asText()).isEqualTo("TAXES");
        assertThat(before.path("totals").get(0).path("amount").asText()).isEqualTo("60.00");

        var changed = send("PATCH", base(ctx) + "/payments/" + active + "/category",
                ctx.owner(), "{\"categoryCode\":\"OTHER\"}", "If-Match", "\"1\"");
        assertThat(changed.status()).as(changed.raw()).isEqualTo(200);
        var after = read(ctx, "categories", date, date, "OUT", ctx.owner(), ctx.account()).body();
        assertThat(after.path("items").get(0).path("categoryCode").asText()).isEqualTo("OTHER");
        assertThat(after.path("items").get(0).path("sharePercent").asText()).isEqualTo("100.00");
        var counterparts = read(ctx, "counterparties", date, date, "OUT", ctx.owner(), ctx.account()).body();
        assertThat(counterparts.path("items")).hasSize(1);
        assertThat(counterparts.path("items").get(0).path("counterpartyKey").asText()).isEqualTo("b");
    }

    @Test
    void emptyReadPermissionsAndRangeValidation() throws Exception {
        var ctx = context();
        LocalDate date = LocalDate.parse("2026-09-02");
        UUID viewer = UUID.randomUUID();
        addMember(ctx.owner(), ctx.company(), viewer, "VIEWER");
        for (String group : new String[] {"categories", "counterparties"}) {
            var empty = read(ctx, group, date, date, null, viewer, ctx.account());
            assertThat(empty.status()).as(empty.raw()).isEqualTo(200);
            assertThat(empty.body().path("items")).isEmpty();
            assertThat(empty.body().path("totals")).hasSize(2);
            assertThat(empty.body().path("totals").get(0).path("amount").asText()).isEqualTo("0.00");
            assertThat(empty.body().path("totals").get(1).path("paymentCount").asLong()).isZero();
            assertThat(read(ctx, group, date, date, null, UUID.randomUUID(), ctx.account()).status())
                    .isEqualTo(404);
            assertThat(read(ctx, group, date, date, null, viewer, UUID.randomUUID()).status())
                    .isEqualTo(404);
            assertThat(read(ctx, group, date, date.minusDays(1), null, ctx.owner(), ctx.account()).status())
                    .isEqualTo(422);
            assertThat(read(ctx, group, date.minusDays(366), date, null, ctx.owner(), ctx.account()).status())
                    .isEqualTo(422);
            assertThat(read(ctx, group, date.minusDays(365), date, null, ctx.owner(), ctx.account()).status())
                    .isEqualTo(200);
        }
    }

    private Ctx context() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Analytics");
        return new Ctx(owner, company, createAccount(owner, company, null));
    }

    private UUID insert(Ctx ctx, LocalDate date, String amount, Direction direction, String category,
                        String key, String inn, String name) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        ledger.insertPayment(new Payment(id, ctx.company(), ctx.account(), null, null, null, date,
                new BigDecimal(amount), direction, key, inn, name, "Test", category, CategorySource.RULE, "R1",
                null, 0, now, now));
        return id;
    }

    private Response read(Ctx ctx, String group, LocalDate from, LocalDate to, String direction,
                          UUID user, UUID account) throws Exception {
        String suffix = direction == null ? "" : "&direction=" + direction;
        return send("GET", base(ctx) + "/analytics/" + group + "?accountId=" + account
                + "&fromDate=" + from + "&toDate=" + to + suffix, user, null);
    }

    private static JsonNode find(JsonNode rows, String key, String value) {
        for (JsonNode row : rows) {
            if (value.equals(row.path(key).asText())) {
                return row;
            }
        }
        throw new AssertionError("Missing " + key + "=" + value);
    }

    private static JsonNode findDirection(JsonNode rows, String key, String direction) {
        for (JsonNode row : rows) {
            JsonNode found = row.path("counterpartyKey");
            if (direction.equals(row.path("direction").asText())
                    && (key == null ? found.isNull() : key.equals(found.asText()))) {
                return row;
            }
        }
        throw new AssertionError("Missing counterparty key/direction");
    }

    private static String base(Ctx ctx) {
        return "/api/v1/orchestrator/companies/" + ctx.company();
    }

    private record Ctx(UUID owner, UUID company, UUID account) {
    }
}
