package ru.cashflow.orchestrator.integrations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.recurring.application.RecurringService;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.BusinessDayRule;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.DetectedCandidate;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Frequency;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Schedule;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurringIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RecurringService service;
    @Autowired
    CompanyAccess access;

    @Test
    void repeatedDetectionKeepsStableIdAndNeverOverwritesUserDecision() throws Exception {
        var ctx = ctx();
        var scope = access.requireWrite(ctx.owner(), ctx.company());
        var first = service.mergeDetection(scope, ctx.account(), "DET1", 1, List.of(rent("100000.00")));
        assertThat(first.created()).isEqualTo(1);
        var pattern = list(ctx).get(0);
        String id = pattern.path("id").asText();
        assertThat(pattern.path("status").asText()).isEqualTo("DETECTED");

        var refreshed = service.mergeDetection(scope, ctx.account(), "DET1", 2, List.of(rent("105000.00")));
        assertThat(refreshed.refreshed()).isEqualTo(1);
        assertThat(list(ctx).get(0).path("id").asText()).isEqualTo(id);
        assertThat(list(ctx).get(0).path("amount").asText()).isEqualTo("105000.00");

        long version = list(ctx).get(0).path("version").asLong();
        var decided = send("PUT", base(ctx) + "/" + id + "/status", ctx.owner(), "{\"status\":\"EXCLUDED\"}",
                "If-Match", "\"" + version + "\"");
        assertThat(decided.status()).isEqualTo(200);
        assertThat(dataVersion(ctx)).isEqualTo(1);

        var again = service.mergeDetection(scope, ctx.account(), "DET2", 3, List.of(rent("999.00")));
        assertThat(again.keptUserDecisions()).isEqualTo(1);
        var after = list(ctx).get(0);
        assertThat(after.path("status").asText()).isEqualTo("EXCLUDED");
        assertThat(after.path("statusSource").asText()).isEqualTo("USER");
        assertThat(after.path("amount").asText()).isEqualTo("105000.00");
        assertThat(jdbc.sql("select count(*) from recurring_revisions").query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void ambiguousCandidatesAreSkippedAndInvalidSchedulesRejected() throws Exception {
        var ctx = ctx();
        var scope = access.requireWrite(ctx.owner(), ctx.company());
        var result = service.mergeDetection(scope, ctx.account(), "DET1", 1, List.of(rent("1.00"), rent("2.00")));
        assertThat(result.ambiguousSkipped()).isEqualTo(1);
        assertThat(list(ctx)).isEmpty();

        var invalid = new DetectedCandidate(Direction.OUT, "RENT", "inn:1", new Schedule(Frequency.WEEKLY, null, 5,
                BusinessDayRule.NONE), new BigDecimal("1.00"), 3, LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-03-01"));
        assertThatThrownBy(() -> service.mergeDetection(scope, ctx.account(), "DET1", 1, List.of(invalid)))
                .hasMessageContaining("инвариант");
    }

    @Test
    void decisionRulesRolesAndVersions() throws Exception {
        var ctx = ctx();
        UUID viewer = UUID.randomUUID();
        addMember(ctx.owner(), ctx.company(), viewer, "VIEWER");
        service.mergeDetection(access.requireWrite(ctx.owner(), ctx.company()), ctx.account(), "DET1", 1,
                List.of(rent("1.00")));
        String id = list(ctx).get(0).path("id").asText();
        String path = base(ctx) + "/" + id + "/status";

        assertThat(send("PUT", path, viewer, "{\"status\":\"CONFIRMED\"}", "If-Match", "\"0\"").status()).isEqualTo(403);
        assertThat(send("PUT", path, ctx.owner(), "{\"status\":\"DETECTED\"}", "If-Match", "\"0\"").status())
                .isEqualTo(422);
        assertThat(send("PUT", path, ctx.owner(), "{\"status\":\"CONFIRMED\"}", "If-Match", "\"9\"").status())
                .isEqualTo(409);
        assertThat(send("PUT", path, ctx.owner(), "{\"status\":\"CONFIRMED\"}", "If-Match", "\"0\"").status())
                .isEqualTo(200);
        assertThat(send("GET", base(ctx), UUID.randomUUID(), null).status()).isEqualTo(404);
    }

    private static DetectedCandidate rent(String amount) {
        return new DetectedCandidate(Direction.OUT, "RENT", "inn:0012345678",
                new Schedule(Frequency.MONTHLY, null, 5, BusinessDayRule.FOLLOWING), new BigDecimal(amount), 12,
                LocalDate.parse("2025-10-05"), LocalDate.parse("2026-09-05"));
    }

    private List<com.fasterxml.jackson.databind.JsonNode> list(Ctx ctx) throws Exception {
        var items = send("GET", base(ctx), ctx.owner(), null).body().path("items");
        var result = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        items.forEach(result::add);
        return result;
    }

    private long dataVersion(Ctx ctx) throws Exception {
        return send("GET", "/api/v1/orchestrator/companies/" + ctx.company(), ctx.owner(), null).body()
                .path("dataVersion").asLong();
    }

    private Ctx ctx() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Регулярности");
        return new Ctx(owner, company, createAccount(owner, company, null));
    }

    private static String base(Ctx ctx) {
        return "/api/v1/orchestrator/companies/" + ctx.company() + "/accounts/" + ctx.account() + "/recurring-patterns";
    }

    private record Ctx(UUID owner, UUID company, UUID account) {
    }
}
