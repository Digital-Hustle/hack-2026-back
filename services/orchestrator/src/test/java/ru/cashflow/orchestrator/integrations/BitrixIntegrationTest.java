package ru.cashflow.orchestrator.integrations;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class BitrixIntegrationTest extends AbstractIntegrationTest {

    @Test
    void demoSyncCreatesIncomingPlansOnceAndRepeatIsNoOp() throws Exception {
        var ctx = ctx();
        String sync = base(ctx) + "/integrations/bitrix24/sync";

        var preview = send("POST", sync, ctx.owner(), "{\"mode\":\"DEMO\",\"dryRun\":true}");
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.body().path("created").asInt()).isEqualTo(5);
        assertThat(preview.body().path("dataChanged").asBoolean()).isFalse();
        assertThat(dataVersion(ctx)).isEqualTo(0);

        var first = send("POST", sync, ctx.owner(), "{\"mode\":\"DEMO\",\"weightByProbability\":true}");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().path("portal").asText()).isEqualTo("demo");
        assertThat(first.body().path("created").asInt()).isEqualTo(5);
        assertThat(first.body().path("skipped").asInt()).isEqualTo(5);
        assertThat(first.body().path("dataChanged").asBoolean()).isTrue();
        assertThat(dataVersion(ctx)).isEqualTo(1);
        assertThat(revisionCount()).isEqualTo(5);

        JsonNode deal = item(first.body(), "demo/deal/101");
        assertThat(deal.path("action").asText()).isEqualTo("CREATED");
        assertThat(deal.path("documentAmount").asText()).isEqualTo("350000.00");
        assertThat(deal.path("planAmount").asText()).isEqualTo("280000.00");
        assertThat(item(first.body(), "demo/deal/104").path("reason").asText()).isEqualTo("LOST");
        assertThat(item(first.body(), "demo/deal/104").path("planAmount").isNull()).isTrue();

        var plan = send("GET", base(ctx) + "/expected-payments/" + deal.path("planId").asText(), ctx.owner(), null)
                .body();
        assertThat(plan.path("direction").asText()).isEqualTo("IN");
        assertThat(plan.path("status").asText()).isEqualTo("OPEN");
        assertThat(plan.path("source").asText()).isEqualTo("CONTRACT");
        assertThat(plan.path("sourceDocument").path("externalSystem").asText()).isEqualTo("BITRIX24");
        assertThat(plan.path("sourceDocument").path("externalId").asText()).isEqualTo("demo/deal/101");

        var repeat = send("POST", sync, ctx.owner(), "{\"mode\":\"DEMO\",\"weightByProbability\":true}");
        assertThat(repeat.body().path("unchanged").asInt()).isEqualTo(5);
        assertThat(repeat.body().path("dataChanged").asBoolean()).isFalse();
        assertThat(dataVersion(ctx)).isEqualTo(1);

        var full = send("POST", sync, ctx.owner(), "{\"mode\":\"DEMO\"}");
        assertThat(full.body().path("updated").asInt()).isEqualTo(3);
        assertThat(dataVersion(ctx)).isEqualTo(2);

        var status = send("GET", base(ctx) + "/integrations/bitrix24", ctx.owner(), null).body();
        assertThat(status.path("activePlans").asInt()).isEqualTo(5);
        assertThat(status.path("activeAmount").asText()).isEqualTo("732000.00");
        assertThat(status.path("demoAvailable").asBoolean()).isTrue();
    }

    @Test
    void fromDateMovesTheSyncWindowToTheForecastDate() throws Exception {
        var ctx = ctx();
        String sync = base(ctx) + "/integrations/bitrix24/sync";

        var report = send("POST", sync, ctx.owner(),
                "{\"mode\":\"DEMO\",\"fromDate\":\"2026-02-01\",\"horizonDays\":30}");
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.body().path("from").asText()).isEqualTo("2026-02-01");
        assertThat(report.body().path("to").asText()).isEqualTo("2026-03-03");
        assertThat(report.body().path("created").asInt()).isEqualTo(5);
        for (JsonNode item : report.body().path("items")) {
            if ("CREATED".equals(item.path("action").asText())) {
                assertThat(item.path("dueDate").asText()).isBetween("2026-02-01", "2026-03-03");
            }
        }
    }

    @Test
    void liveSyncRequiresAllowedHttpsWebhookAndWriteRole() throws Exception {
        var ctx = ctx();
        String sync = base(ctx) + "/integrations/bitrix24/sync";

        var missing = send("POST", sync, ctx.owner(), "{}");
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.body().path("code").asText()).isEqualTo("BITRIX_NOT_CONFIGURED");
        var internal = send("POST", sync, ctx.owner(), "{\"webhookUrl\":\"https://169.254.169.254/rest/1/secret123/\"}");
        assertThat(internal.status()).isEqualTo(422);
        assertThat(internal.body().path("code").asText()).isEqualTo("BITRIX_WEBHOOK_INVALID");
        assertThat(internal.raw()).doesNotContain("secret123");

        UUID viewer = UUID.randomUUID();
        addMember(ctx.owner(), ctx.company(), viewer, "VIEWER");
        assertThat(send("POST", sync, viewer, "{\"mode\":\"DEMO\"}").status()).isEqualTo(403);
        assertThat(send("GET", base(ctx) + "/integrations/bitrix24", viewer, null).status()).isEqualTo(200);
        assertThat(send("GET", base(ctx) + "/integrations/bitrix24", UUID.randomUUID(), null).status()).isEqualTo(404);
    }

    private Ctx ctx() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Битрикс");
        return new Ctx(owner, company, createAccount(owner, company, null));
    }

    private long dataVersion(Ctx ctx) throws Exception {
        return send("GET", "/api/v1/orchestrator/companies/" + ctx.company(), ctx.owner(), null).body()
                .path("dataVersion").asLong();
    }

    private long revisionCount() {
        return jdbc.sql("select count(*) from expected_payment_revisions").query(Long.class).single();
    }

    private static JsonNode item(JsonNode report, String externalId) {
        for (JsonNode item : report.path("items")) {
            if (item.path("externalId").asText().equals(externalId)) {
                return item;
            }
        }
        throw new AssertionError("no item " + externalId);
    }

    private static String base(Ctx ctx) {
        return "/api/v1/orchestrator/companies/" + ctx.company() + "/accounts/" + ctx.account();
    }

    private record Ctx(UUID owner, UUID company, UUID account) {
    }
}
