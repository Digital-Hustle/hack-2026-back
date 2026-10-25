package ru.cashflow.orchestrator.integrations;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class OneCIntegrationTest extends AbstractIntegrationTest {

    @Test
    void demoRegisterIsLoadedOnceMovedByFromDateAndRepeatIsNoOp() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "1С");
        UUID account = createAccount(owner, company, null);
        String base = "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;
        String sync = base + "/integrations/onec/sync";

        var preview = send("POST", sync, owner, "{\"fromDate\":\"2026-02-01\",\"dryRun\":true}");
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.body().path("created").asInt()).isEqualTo(3);
        assertThat(preview.body().path("dataChanged").asBoolean()).isFalse();
        assertThat(count()).isZero();

        var first = send("POST", sync, owner, "{\"fromDate\":\"2026-02-01\"}");
        assertThat(first.body().path("created").asInt()).isEqualTo(3);
        assertThat(first.body().path("dataChanged").asBoolean()).isTrue();
        assertThat(first.body().path("items").get(0).path("dueDate").asText()).isEqualTo("2026-02-04");
        assertThat(count()).isEqualTo(3);

        var repeat = send("POST", sync, owner, "{\"fromDate\":\"2026-02-01\"}");
        assertThat(repeat.body().path("unchanged").asInt()).isEqualTo(3);
        assertThat(repeat.body().path("dataChanged").asBoolean()).isFalse();

        var moved = send("POST", sync, owner, "{\"fromDate\":\"2026-03-01\"}");
        assertThat(moved.body().path("updated").asInt()).isEqualTo(3);
        assertThat(moved.body().path("items").get(2).path("dueDate").asText()).isEqualTo("2026-03-17");
        assertThat(count()).isEqualTo(3);

        var plan = send("GET", base + "/expected-payments/" + moved.body().path("items").get(2).path("planId").asText(),
                owner, null).body();
        assertThat(plan.path("direction").asText()).isEqualTo("IN");
        assertThat(plan.path("source").asText()).isEqualTo("INVOICE");
        assertThat(plan.path("counterpartyKey").asText()).isEqualTo("inn:7701234567");
        assertThat(plan.path("sourceDocument").path("externalSystem").asText()).isEqualTo("1C_DEMO");
    }

    @Test
    void viewerCannotLoadTheRegister() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "1С");
        UUID account = createAccount(owner, company, null);
        UUID viewer = UUID.randomUUID();
        addMember(owner, company, viewer, "VIEWER");
        String sync = "/api/v1/orchestrator/companies/" + company + "/accounts/" + account + "/integrations/onec/sync";

        assertThat(send("POST", sync, viewer, "{}").status()).isEqualTo(403);
        assertThat(send("POST", sync, UUID.randomUUID(), "{}").status()).isEqualTo(404);
    }

    private long count() {
        return jdbc.sql("select count(*) from expected_payments").query(Long.class).single();
    }
}
