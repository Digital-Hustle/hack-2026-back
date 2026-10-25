package ru.cashflow.orchestrator.integrations;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class ManualPaymentIntegrationTest extends AbstractIntegrationTest {

    @Test
    void ownerCreatesManualFactAndItIsVisibleInPaymentsAndDailyHistory() throws Exception {
        var ctx = context();
        LocalDate date = companyToday(ctx.company()).minusDays(10);
        String path = companyBase(ctx) + "/payments";

        var created = send("POST", path, ctx.owner(), body(ctx, date, "1500.00", "OUT", "Аренда", "RENT", "касса"),
                "Idempotency-Key", "manual-key-1");
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        assertThat(created.body().path("source").asText()).isEqualTo("MANUAL");
        assertThat(created.body().path("note").asText()).isEqualTo("касса");
        assertThat(created.body().path("importId").isNull()).isTrue();
        assertThat(created.body().path("externalOperationId").isNull()).isTrue();
        assertThat(created.body().path("categoryCode").asText()).isEqualTo("RENT");
        assertThat(created.body().path("categorySource").asText()).isEqualTo("USER");
        assertThat(created.body().path("amount").asText()).isEqualTo("1500.00");
        String paymentId = created.body().path("id").asText();

        var page = send("GET", base(ctx) + "/payments?from=" + date + "&to=" + date, ctx.owner(), null);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().path("items")).hasSize(1);
        assertThat(page.body().path("items").get(0).path("id").asText()).isEqualTo(paymentId);
        var publicPage = send("GET", companyBase(ctx) + "/payments?accountId=" + ctx.account(), ctx.owner(), null);
        assertThat(publicPage.body().path("items").get(0).path("source").asText()).isEqualTo("MANUAL");

        assertThat(send("POST", base(ctx) + "/completeness", ctx.owner(),
                "{\"fromDate\":\"" + date + "\",\"toDate\":\"" + date
                        + "\",\"completeness\":\"CONFIRMED_COMPLETE\",\"reason\":\"касса\"}").status()).isEqualTo(201);
        var history = send("GET", base(ctx) + "/daily-history?from=" + date + "&to=" + date, ctx.owner(), null);
        assertThat(history.body().path("items").get(0).path("status").asText()).as(history.raw()).isEqualTo("OBSERVED");
        assertThat(history.body().path("items").get(0).path("outflow").asText()).isEqualTo("1500.00");
        assertThat(dataVersion(ctx)).isEqualTo(2);
    }

    @Test
    void manualFactIsFoundBySearchByPurposeNoteInnAndAmount() throws Exception {
        var ctx = context();
        LocalDate date = companyToday(ctx.company()).minusDays(50);
        String payload = body(ctx, date, "1234.50", "OUT", "Аренда склада", "RENT", "заметка про кофе")
                .replace("\"counterpartyName\"", "\"counterpartyInn\":\"7707083893\",\"counterpartyName\"");
        assertThat(send("POST", companyBase(ctx) + "/payments", ctx.owner(), payload,
                "Idempotency-Key", "manual-key-search").status()).isEqualTo(201);

        for (String q : new String[] {"контрагент", "аренда", "кофе", "7707083893", "1234.50"}) {
            var page = send("GET", companyBase(ctx) + "/payments?accountId=" + ctx.account() + "&q="
                    + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8), ctx.owner(), null);
            assertThat(page.body().path("items")).as("q=" + q).hasSize(1);
        }
        var none = send("GET", companyBase(ctx) + "/payments?accountId=" + ctx.account() + "&q=nothing-here",
                ctx.owner(), null);
        assertThat(none.body().path("items")).isEmpty();
    }

    @Test
    void replayReturnsSameFactAndDifferentBodyConflicts() throws Exception {
        var ctx = context();
        LocalDate date = companyToday(ctx.company()).minusDays(7);
        String path = companyBase(ctx) + "/payments";
        String body = body(ctx, date, "100.00", "OUT", "Канцтовары", "OFFICE_SUPPLIES", null);

        var first = send("POST", path, ctx.owner(), body, "Idempotency-Key", "manual-key-replay");
        var replay = send("POST", path, ctx.owner(), body, "Idempotency-Key", "manual-key-replay");
        var other = send("POST", path, ctx.owner(), body(ctx, date, "101.00", "OUT", "Канцтовары",
                "OFFICE_SUPPLIES", null), "Idempotency-Key", "manual-key-replay");

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().path("id").asText()).isEqualTo(first.body().path("id").asText());
        assertThat(other.status()).isEqualTo(409);
        assertThat(jdbc.sql("select count(*) from payments").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void rolesTenantAndClosedDayRulesAreEnforced() throws Exception {
        var ctx = context();
        UUID viewer = UUID.randomUUID();
        addMember(ctx.owner(), ctx.company(), viewer, "VIEWER");
        LocalDate date = companyToday(ctx.company()).minusDays(5);
        String path = companyBase(ctx) + "/payments";

        assertThat(send("POST", path, viewer, body(ctx, date, "10.00", "OUT", "x", "OTHER", null),
                "Idempotency-Key", "manual-key-viewer").status()).isEqualTo(403);
        assertThat(send("POST", path, UUID.randomUUID(), body(ctx, date, "10.00", "OUT", "x", "OTHER", null),
                "Idempotency-Key", "manual-key-stranger").status()).isEqualTo(404);
        var otherCompany = context();
        assertThat(send("POST", path, ctx.owner(), body(otherCompany, date, "10.00", "OUT", "x", "OTHER", null),
                "Idempotency-Key", "manual-key-foreign-account").status()).isEqualTo(404);
        assertThat(send("POST", path, ctx.owner(), body(ctx, companyToday(ctx.company()), "10.00", "OUT", "x", "OTHER", null),
                "Idempotency-Key", "manual-key-today").status()).isEqualTo(422);
        assertThat(send("POST", path, ctx.owner(), body(ctx, date, "0.00", "OUT", "x", "OTHER", null),
                "Idempotency-Key", "manual-key-zero").status()).isEqualTo(422);
        assertThat(send("POST", path, ctx.owner(), body(ctx, date, "10.00", "OUT", "x", "UNKNOWN_CATEGORY", null),
                "Idempotency-Key", "manual-key-bad-category").status()).isEqualTo(422);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Pacific/Kiritimati", "Pacific/Honolulu"})
    void manualPaymentUsesCompanyLocalDateForOpenDay(String timezone) throws Exception {
        var ctx = context();
        jdbc.sql("update companies set timezone=:timezone where id=:company")
                .param("timezone", timezone).param("company", ctx.company()).update();
        LocalDate today = companyToday(ctx.company());
        String path = companyBase(ctx) + "/payments";

        var openDay = send("POST", path, ctx.owner(), body(ctx, today, "10.00", "OUT", "x", "OTHER", null),
                "Idempotency-Key", "manual-key-open-day");
        assertThat(openDay.status()).as(openDay.raw()).isEqualTo(422);
        assertThat(openDay.body().path("code").asText()).isEqualTo("OPEN_DAY");

        var closedDay = send("POST", path, ctx.owner(), body(ctx, today.minusDays(1), "10.00", "OUT", "x",
                "OTHER", null), "Idempotency-Key", "manual-key-closed-day");
        assertThat(closedDay.status()).as(closedDay.raw()).isEqualTo(201);
    }

    @Test
    void aCompleteDayCannotBeChangedByAManualFact() throws Exception {
        var ctx = context();
        LocalDate date = companyToday(ctx.company()).minusDays(3);
        assertThat(send("POST", base(ctx) + "/completeness", ctx.owner(),
                "{\"fromDate\":\"" + date + "\",\"toDate\":\"" + date
                        + "\",\"completeness\":\"CONFIRMED_COMPLETE\",\"reason\":\"выписка\"}").status()).isEqualTo(201);

        var response = send("POST", companyBase(ctx) + "/payments", ctx.owner(),
                body(ctx, date, "10.00", "OUT", "x", "OTHER", null), "Idempotency-Key", "manual-key-complete");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.body().path("code").asText()).isEqualTo("DAY_ALREADY_COMPLETE");
        assertThat(jdbc.sql("select count(*) from payments").query(Long.class).single()).isZero();
        assertThat(dataVersion(ctx)).isEqualTo(1);
    }

    @Test
    void manualFactCanBeAllocatedToAnExpectedPayment() throws Exception {
        var ctx = context();
        LocalDate date = companyToday(ctx.company()).minusDays(4);
        var payment = send("POST", companyBase(ctx) + "/payments", ctx.owner(),
                body(ctx, date, "250.00", "OUT", "Аренда", "RENT", null), "Idempotency-Key", "manual-key-plan");
        var plan = send("POST", base(ctx) + "/expected-payments", ctx.owner(),
                "{\"title\":\"Аренда\",\"direction\":\"OUT\",\"amount\":\"250.00\",\"dueDate\":\"" + date + "\","
                        + "\"categoryCode\":\"RENT\",\"relation\":\"ADDITIONAL\",\"source\":\"MANUAL\","
                        + "\"sourceDocument\":null}");
        assertThat(plan.status()).as(plan.raw()).isEqualTo(201);

        var allocation = send("POST", base(ctx) + "/expected-payments/" + plan.body().path("id").asText()
                        + "/allocations", ctx.owner(),
                "{\"paymentId\":\"" + payment.body().path("id").asText() + "\",\"amount\":\"250.00\"}");
        assertThat(allocation.status()).as(allocation.raw()).isEqualTo(201);
        assertThat(send("GET", base(ctx) + "/expected-payments/" + plan.body().path("id").asText(), ctx.owner(), null)
                .body().path("settledAmount").asText()).isEqualTo("250.00");
    }


    @org.springframework.beans.factory.annotation.Autowired
    ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository ledger;

    @org.springframework.beans.factory.annotation.Autowired
    ru.cashflow.orchestrator.features.companies.application.CompanyAccess access;

    private String createManual(Ctx ctx, LocalDate date) throws Exception {
        var response = send("POST", companyBase(ctx) + "/payments", ctx.owner(),
                body(ctx, date, "250.00", "OUT", "Аренда", "RENT", null),
                "Idempotency-Key", "correction-" + UUID.randomUUID());
        assertThat(response.status()).as(response.raw()).isEqualTo(201);
        assertThat(response.body().path("dayStatus").asText()).isEqualTo("MISSING");
        assertThat(response.body().path("requiresCompletenessConfirmation").asBoolean()).isTrue();
        return response.body().path("id").asText();
    }

    private String createPlan(Ctx ctx, String payment) throws Exception {
        var plan = send("POST", base(ctx) + "/expected-payments", ctx.owner(),
                "{\"title\":\"Аренда\",\"direction\":\"OUT\",\"amount\":\"250.00\",\"dueDate\":\""
                        + companyToday(ctx.company()).minusDays(4) + "\",\"categoryCode\":\"RENT\","
                        + "\"relation\":\"ADDITIONAL\",\"source\":\"MANUAL\"}");
        assertThat(plan.status()).as(plan.raw()).isEqualTo(201);
        String id = plan.body().path("id").asText();
        var allocation = send("POST", base(ctx) + "/expected-payments/" + id + "/allocations", ctx.owner(),
                "{\"paymentId\":\"" + payment + "\",\"amount\":\"250.00\"}");
        assertThat(allocation.status()).as(allocation.raw()).isEqualTo(201);
        return id;
    }

    private UUID imported(Ctx ctx, String manual) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into payments(id,company_id,account_id,operation_date,amount,direction,category_code,"
                        + "category_source,known_at,created_at,source) select :id,company_id,account_id,operation_date,"
                        + "amount,direction,category_code,category_source,now(),now(),'IMPORT' from payments where id=:manual")
                .param("id", id).param("manual", UUID.fromString(manual)).update();
        return id;
    }

    @Test
    void voidRestoresPlanAndPreservesHistoricalFactWithoutCreatingOppositeFlow() throws Exception {
        var ctx = context();
        LocalDate date = companyToday(ctx.company()).minusDays(4);
        String payment = createManual(ctx, date);
        String plan = createPlan(ctx, payment);
        var scope = access.requireRead(ctx.owner(), ctx.company());
        java.time.Instant cutoff = java.time.Instant.now();
        var response = send("POST", companyBase(ctx) + "/payments/" + payment + "/void", ctx.owner(),
                "{\"reason\":\"Ошибка суммы\"}", "If-Match", "\"1\"");
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        assertThat(response.body().path("status").asText()).isEqualTo("VOIDED");
        assertThat(response.body().path("version").asLong()).isEqualTo(2);
        assertThat(send("GET", base(ctx) + "/expected-payments/" + plan, ctx.owner(), null)
                .body().path("settledAmount").asText()).isEqualTo("0.00");
        assertThat(ledger.paymentsKnownBefore(scope, ctx.account(), date, date, cutoff)).hasSize(1);
        assertThat(ledger.paymentsKnownBefore(scope, ctx.account(), date, date, java.time.Instant.now())).isEmpty();
        assertThat(send("GET", companyBase(ctx) + "/payments?accountId=" + ctx.account(), ctx.owner(), null)
                .body().path("items")).isEmpty();
        assertThat(jdbc.sql("select count(*) from payments").query(Long.class).single()).isEqualTo(1);
        assertThat(send("POST", base(ctx) + "/expected-payments/" + plan + "/allocations", ctx.owner(),
                "{\"paymentId\":\"" + payment + "\",\"amount\":\"1.00\"}").status()).isEqualTo(409);
    }

    @Test
    void sameDuplicateTransfersSettlementAndRetryDoesNotRepeatIt() throws Exception {
        var ctx = context();
        String manual = createManual(ctx, companyToday(ctx.company()).minusDays(4));
        String plan = createPlan(ctx, manual);
        UUID bank = imported(ctx, manual);
        String path = companyBase(ctx) + "/payments/" + manual;
        assertThat(send("GET", path + "/duplicate-candidates", ctx.owner(), null).body().path("items")).hasSize(1);
        String body = "{\"importedPaymentId\":\"" + bank
                + "\",\"importedPaymentVersion\":1,\"decision\":\"SAME\",\"reason\":\"Дубль выписки\"}";
        var response = send("POST", path + "/duplicate-resolutions", ctx.owner(), body, "If-Match", "\"1\"");
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        assertThat(response.body().path("replacementPaymentId").asText()).isEqualTo(bank.toString());
        assertThat(send("POST", path + "/duplicate-resolutions", ctx.owner(), body, "If-Match", "\"1\"")
                .status()).isEqualTo(200);
        assertThat(send("GET", base(ctx) + "/expected-payments/" + plan, ctx.owner(), null)
                .body().path("settledAmount").asText()).isEqualTo("250.00");
        assertThat(jdbc.sql("select count(*) from allocations where reversed_at is null and payment_id=:id")
                .param("id", bank).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from payment_voids").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void differentDecisionKeepsBothFactsAndRemovesCandidate() throws Exception {
        var ctx = context();
        String manual = createManual(ctx, companyToday(ctx.company()).minusDays(4));
        UUID bank = imported(ctx, manual);
        String path = companyBase(ctx) + "/payments/" + manual;
        String body = "{\"importedPaymentId\":\"" + bank
                + "\",\"importedPaymentVersion\":1,\"decision\":\"DIFFERENT\",\"reason\":\"Разные оплаты\"}";
        assertThat(send("POST", path + "/duplicate-resolutions", ctx.owner(), body, "If-Match", "\"1\"")
                .status()).isEqualTo(200);
        assertThat(send("GET", path + "/duplicate-candidates", ctx.owner(), null).body().path("items")).isEmpty();
        assertThat(send("GET", companyBase(ctx) + "/payments?accountId=" + ctx.account(), ctx.owner(), null)
                .body().path("items")).hasSize(2);
    }

    @Test
    void correctionChecksRoleTenantVersionAndReason() throws Exception {
        var ctx = context();
        String manual = createManual(ctx, companyToday(ctx.company()).minusDays(4));
        UUID viewer = UUID.randomUUID();
        addMember(ctx.owner(), ctx.company(), viewer, "VIEWER");
        String path = companyBase(ctx) + "/payments/" + manual + "/void";
        String body = "{\"reason\":\"Ошибка\"}";
        assertThat(send("POST", path, viewer, body, "If-Match", "\"1\"").status()).isEqualTo(403);
        assertThat(send("POST", path, UUID.randomUUID(), body, "If-Match", "\"1\"").status()).isEqualTo(404);
        assertThat(send("POST", path, ctx.owner(), body, "If-Match", "\"2\"").status()).isEqualTo(409);
        assertThat(send("POST", path, ctx.owner(), "{\"reason\":\" \"}", "If-Match", "\"1\"")
                .status()).isEqualTo(422);
        assertThat(jdbc.sql("select count(*) from payment_voids").query(Long.class).single()).isZero();
    }


    @Test
    void concurrentVoidsApplyOnce() throws Exception {
        var ctx = context();
        String manual = createManual(ctx, companyToday(ctx.company()).minusDays(4));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> request = () -> send("POST",
                    companyBase(ctx) + "/payments/" + manual + "/void", ctx.owner(),
                    "{\"reason\":\"Ошибка\"}", "If-Match", "\"1\"").status();
            var responses = executor.invokeAll(java.util.List.of(request, request));
            assertThat(java.util.List.of(responses.get(0).get(), responses.get(1).get()))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.sql("select count(*) from payment_voids").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void overallocatedTargetRollsBackAndForeignTargetIsHidden() throws Exception {
        var ctx = context();
        String manual = createManual(ctx, companyToday(ctx.company()).minusDays(4));
        String plan = createPlan(ctx, manual);
        UUID bank = imported(ctx, manual);
        createPlan(ctx, bank.toString());
        String path = companyBase(ctx) + "/payments/" + manual + "/duplicate-resolutions";
        String template = "{\"importedPaymentId\":\"%s\",\"importedPaymentVersion\":1,"
                + "\"decision\":\"SAME\",\"reason\":\"Дубль\"}";
        assertThat(send("POST", path, ctx.owner(), template.formatted(bank), "If-Match", "\"1\"")
                .status()).isEqualTo(409);
        assertThat(send("POST", path, ctx.owner(), template.formatted(UUID.randomUUID()), "If-Match", "\"1\"")
                .status()).isEqualTo(404);
        assertThat(send("GET", base(ctx) + "/expected-payments/" + plan, ctx.owner(), null)
                .body().path("settledAmount").asText()).isEqualTo("250.00");
        assertThat(jdbc.sql("select count(*) from payment_voids").query(Long.class).single()).isZero();
    }

    private Ctx context() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Ручные платежи");
        UUID account = createAccount(owner, company, null);
        return new Ctx(owner, company, account);
    }

    private static String body(Ctx ctx, LocalDate date, String amount, String direction, String purpose,
                               String category, String note) {
        return "{\"accountId\":\"" + ctx.account() + "\",\"operationDate\":\"" + date
                + "\",\"amount\":\"" + amount + "\",\"direction\":\"" + direction
                + "\",\"counterpartyName\":\"Контрагент\",\"purpose\":\"" + purpose
                + "\",\"categoryCode\":\"" + category + "\",\"note\":"
                + (note == null ? "null" : "\"" + note + "\"") + "}";
    }

    private long dataVersion(Ctx ctx) throws Exception {
        return send("GET", "/api/v1/orchestrator/companies/" + ctx.company(), ctx.owner(), null)
                .body().path("dataVersion").asLong();
    }

    private static String base(Ctx ctx) {
        return "/api/v1/orchestrator/companies/" + ctx.company() + "/accounts/" + ctx.account();
    }

    private static String companyBase(Ctx ctx) {
        return "/api/v1/orchestrator/companies/" + ctx.company();
    }

    private record Ctx(UUID owner, UUID company, UUID account) {
    }
}
