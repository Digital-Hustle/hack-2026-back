package ru.cashflow.orchestrator.integrations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class PublicApiCompletionIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/orchestrator/companies";

    @Autowired
    LedgerRepository ledger;

    @Test
    void capabilitiesExposeConfiguredLimitsAndRequireIdentity() throws Exception {
        UUID user = UUID.randomUUID();

        var response = send("GET", "/api/v1/orchestrator/capabilities", user, null);
        assertThat(response.status()).isEqualTo(200);
        var body = response.body();
        assertThat(body.path("apiVersion").asText()).isNotBlank();
        assertThat(body.path("apiVersion").asText().length()).isLessThanOrEqualTo(30);
        assertThat(body.path("currency").asText()).isEqualTo("RUB");
        assertThat(body.path("forecastHorizons").get(0).asInt()).isEqualTo(30);
        assertThat(body.path("riskMethods").get(0).asText()).isEqualTo("HISTORICAL_TRAJECTORY_BOOTSTRAP");
        assertThat(body.path("maxCsvBytes").asLong()).isEqualTo(20_971_520L);
        assertThat(body.path("maxImportRows").asInt()).isEqualTo(50_000);
        assertThat(body.path("maxPageSize").asInt()).isEqualTo(200);

        assertThat(send("GET", "/api/v1/orchestrator/capabilities", null, null).status()).isEqualTo(401);
    }

    @Test
    void dataQualityCountsRealDaysMissingDaysMismatchesAndEmptyPurposes() throws Exception {
        Ctx ctx = ctx();
        payment(ctx, "2026-09-01", "10.00", Direction.IN, "Клиент");
        payment(ctx, "2026-09-04", "20.00", Direction.OUT, null);
        observation(ctx, "2026-09-01", "OBSERVED", 1, false);
        observation(ctx, "2026-09-02", "MISSING", 0, false);
        observation(ctx, "2026-09-03", "ZERO_CONFIRMED", 0, false);
        observation(ctx, "2026-09-04", "OBSERVED", 1, true);

        var response = send("GET", base(ctx) + "/data-quality", ctx.owner(), null);
        assertThat(response.status()).isEqualTo(200);
        var body = response.body();
        assertThat(body.path("accountId").asText()).isEqualTo(ctx.account().toString());
        assertThat(body.path("fromDate").asText()).isEqualTo("2026-09-01");
        assertThat(body.path("toDate").asText()).isEqualTo("2026-09-04");
        assertThat(body.path("quality").asText()).isEqualTo("LIMITED");
        assertThat(body.path("completeDayCount").asInt()).isEqualTo(3);
        assertThat(body.path("missingDayCount").asInt()).isEqualTo(1);
        assertThat(body.path("balanceMismatchCount").asInt()).isEqualTo(1);
        assertThat(body.path("emptyPurposeCount").asInt()).isEqualTo(1);
        assertThat(body.path("warnings").toString()).contains("MISSING_DATA", "BALANCE_MISMATCH", "SHORT_HISTORY",
                "2026-09-02");

        UUID viewer = UUID.randomUUID();
        addMember(ctx.owner(), ctx.company(), viewer, "VIEWER");
        assertThat(send("GET", base(ctx) + "/data-quality", viewer, null).status()).isEqualTo(200);
        assertThat(send("GET", base(ctx) + "/data-quality", UUID.randomUUID(), null).status()).isEqualTo(404);
        assertThat(send("GET", base(ctx) + "/data-quality", null, null).status()).isEqualTo(401);
    }

    @Test
    void dataQualityOfAccountWithoutOperationsIsInsufficient() throws Exception {
        Ctx ctx = ctx();

        var body = send("GET", base(ctx) + "/data-quality", ctx.owner(), null).body();
        assertThat(body.path("quality").asText()).isEqualTo("INSUFFICIENT");
        assertThat(body.path("fromDate").isNull()).isTrue();
        assertThat(body.path("toDate").isNull()).isTrue();
        assertThat(body.path("completeDayCount").asInt()).isZero();
        assertThat(body.path("warnings").get(0).path("code").asText()).isEqualTo("SHORT_HISTORY");
    }

    @Test
    void completenessRangesUseThePageEnvelope() throws Exception {
        Ctx ctx = ctx();
        assertThat(send("POST", base(ctx) + "/completeness", ctx.owner(), range("2026-09-01", "2026-09-02")).status())
                .isEqualTo(201);
        assertThat(send("POST", base(ctx) + "/completeness", ctx.owner(), range("2026-09-03", "2026-09-04")).status())
                .isEqualTo(201);

        var first = send("GET", base(ctx) + "/completeness-ranges?page=0&size=1", ctx.owner(), null);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().path("items")).hasSize(1);
        var item = first.body().path("items").get(0);
        assertThat(item.path("id").asText()).isNotBlank();
        assertThat(item.path("accountId").asText()).isEqualTo(ctx.account().toString());
        assertThat(item.path("completeness").asText()).isEqualTo("CONFIRMED_COMPLETE");
        assertThat(item.path("reason").asText()).isEqualTo("выписка");
        assertThat(item.path("createdAt").isTextual()).isTrue();
        assertThat(first.body().path("page").path("page").asInt()).isZero();
        assertThat(first.body().path("page").path("size").asInt()).isEqualTo(1);
        assertThat(first.body().path("page").path("total").asLong()).isEqualTo(2);
        assertThat(first.body().path("page").path("hasNext").asBoolean()).isTrue();

        assertThat(send("GET", base(ctx) + "/completeness-ranges?size=201", ctx.owner(), null).status()).isEqualTo(422);
        assertThat(send("GET", base(ctx) + "/completeness-ranges", UUID.randomUUID(), null).status()).isEqualTo(404);
        assertThat(send("GET", base(ctx) + "/completeness-ranges", null, null).status()).isEqualTo(401);
    }

    @Test
    void dailyActualsAliasAcceptsContractAndLegacyDateNames() throws Exception {
        Ctx ctx = ctx();
        observation(ctx, "2026-09-01", "OBSERVED", 1, false);
        observation(ctx, "2026-09-02", "MISSING", 0, false);

        var contract = send("GET", base(ctx) + "/daily-actuals?fromDate=2026-09-01&toDate=2026-09-02",
                ctx.owner(), null);
        assertThat(contract.status()).isEqualTo(200);
        assertThat(contract.body().path("items")).hasSize(2);
        assertThat(contract.body().path("items").get(0).path("date").asText()).isEqualTo("2026-09-01");
        assertThat(contract.body().path("items").get(0).path("status").asText()).isEqualTo("OBSERVED");
        assertThat(contract.body().path("items").get(1).path("balanceEod").isNull()).isTrue();

        var legacy = send("GET", base(ctx) + "/daily-actuals?from=2026-09-01&to=2026-09-02", ctx.owner(), null);
        assertThat(legacy.status()).isEqualTo(200);
        assertThat(legacy.body().path("items")).hasSize(2);

        assertThat(send("GET", base(ctx) + "/daily-history?from=2026-09-01&to=2026-09-02", ctx.owner(), null)
                .status()).isEqualTo(200);
        assertThat(send("GET", base(ctx) + "/daily-actuals", ctx.owner(), null).status()).isEqualTo(400);
        assertThat(send("GET", base(ctx) + "/daily-actuals?fromDate=2026-09-01&toDate=2026-09-02",
                null, null).status()).isEqualTo(401);
    }

    @Test
    void expectedPaymentImportIsAtomicIdempotentAndVersioned() throws Exception {
        Ctx ctx = ctx();
        String body = importBody("INVOICE_REGISTER",
                importItem(ctx.account(), "INV-1", "Счёт 1", "100.00", null),
                importItem(ctx.account(), "INV-2", "Счёт 2", "200.00", null));

        var created = send("POST", BASE + "/" + ctx.company() + "/expected-payment-imports", ctx.owner(), body,
                "Idempotency-Key", "import-key-0001");
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("createdCount").asInt()).isEqualTo(2);
        assertThat(created.body().path("updatedCount").asInt()).isZero();
        assertThat(created.body().path("unchangedCount").asInt()).isZero();
        assertThat(created.body().path("items")).hasSize(2);
        assertThat(created.body().path("items").get(0).path("source").asText()).isEqualTo("INVOICE");
        assertThat(created.body().path("items").get(0).path("version").asLong()).isZero();
        String firstId = created.body().path("items").get(0).path("id").asText();

        var replay = send("POST", BASE + "/" + ctx.company() + "/expected-payment-imports", ctx.owner(), body,
                "Idempotency-Key", "import-key-0001");
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().path("createdCount").asInt()).isEqualTo(2);
        assertThat(replay.body().path("items").get(0).path("id").asText()).isEqualTo(firstId);
        assertThat(jdbc.sql("select count(*) from expected_payments").query(Long.class).single()).isEqualTo(2);

        var unchanged = send("POST", BASE + "/" + ctx.company() + "/expected-payment-imports", ctx.owner(), body,
                "Idempotency-Key", "import-key-0002");
        assertThat(unchanged.body().path("createdCount").asInt()).isZero();
        assertThat(unchanged.body().path("updatedCount").asInt()).isZero();
        assertThat(unchanged.body().path("unchangedCount").asInt()).isEqualTo(2);

        String changed = importBody("INVOICE_REGISTER",
                importItem(ctx.account(), "INV-1", "Счёт 1 (изменён)", "150.00", 0L),
                importItem(ctx.account(), "INV-2", "Счёт 2", "200.00", null));
        var updated = send("POST", BASE + "/" + ctx.company() + "/expected-payment-imports", ctx.owner(), changed,
                "Idempotency-Key", "import-key-0003");
        assertThat(updated.status()).as(updated.raw()).isEqualTo(201);
        assertThat(updated.body().path("updatedCount").asInt()).isEqualTo(1);
        assertThat(updated.body().path("unchangedCount").asInt()).isEqualTo(1);
        assertThat(updated.body().path("items").get(0).path("version").asLong()).isEqualTo(1);

        var stale = send("POST", BASE + "/" + ctx.company() + "/expected-payment-imports", ctx.owner(), changed,
                "Idempotency-Key", "import-key-0004");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().path("code").asText()).isEqualTo("VERSION_CONFLICT");

        var reused = send("POST", BASE + "/" + ctx.company() + "/expected-payment-imports", ctx.owner(),
                importBody("TAX_REGISTER", importItem(ctx.account(), "INV-1", "Другое", "1.00", null)),
                "Idempotency-Key", "import-key-0001");
        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.body().path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void expectedPaymentImportRejectsForeignArchivedAndInsufficientRoles() throws Exception {
        Ctx ctx = ctx();
        UUID foreignOwner = UUID.randomUUID();
        UUID foreignCompany = createCompany(foreignOwner, "Чужая");
        UUID foreignAccount = createAccount(foreignOwner, foreignCompany, null);
        UUID viewer = UUID.randomUUID();
        addMember(ctx.owner(), ctx.company(), viewer, "VIEWER");

        String foreign = importBody("INVOICE_REGISTER",
                importItem(foreignAccount, "INV-9", "Чужой", "10.00", null));
        assertThat(send("POST", BASE + "/" + ctx.company() + "/expected-payment-imports", ctx.owner(), foreign,
                "Idempotency-Key", "import-key-foreign").status()).isEqualTo(404);

        jdbc.sql("update accounts set archived = true where id = :id").param("id", ctx.account()).update();
        String archived = importBody("INVOICE_REGISTER",
                importItem(ctx.account(), "INV-3", "Архив", "10.00", null));
        assertThat(send("POST", BASE + "/" + ctx.company() + "/expected-payment-imports", ctx.owner(), archived,
                "Idempotency-Key", "import-key-archived").status()).isEqualTo(404);

        jdbc.sql("update accounts set archived = false where id = :id").param("id", ctx.account()).update();
        assertThat(send("POST", BASE + "/" + ctx.company() + "/expected-payment-imports", viewer,
                importBody("INVOICE_REGISTER", importItem(ctx.account(), "INV-4", "Просмотр", "10.00", null)),
                "Idempotency-Key", "import-key-viewer").status()).isEqualTo(403);
        assertThat(send("POST", BASE + "/" + ctx.company() + "/expected-payment-imports", null,
                importBody("INVOICE_REGISTER", importItem(ctx.account(), "INV-5", "Аноним", "10.00", null)),
                "Idempotency-Key", "import-key-anon").status()).isEqualTo(401);
        assertThat(send("POST", BASE + "/" + ctx.company() + "/expected-payment-imports", ctx.owner(),
                importBody("INVOICE_REGISTER", importItem(ctx.account(), "INV-6", "Без ключа", "10.00", null)))
                .status()).isEqualTo(428);

        assertThat(send("POST", BASE + "/" + ctx.company() + "/expected-payment-imports", ctx.owner(),
                importBody("INVOICE_REGISTER"), "Idempotency-Key", "import-key-empty").status()).isEqualTo(422);
    }

    @Test
    void deleteAllocationReversesLikeExistingReverseEndpoint() throws Exception {
        Ctx ctx = ctx();
        String planId = send("POST", base(ctx) + "/expected-payments", ctx.owner(), plan("100.00")).body()
                .path("id").asText();
        UUID pay = payment(ctx, "2026-09-15", "100.00", Direction.IN, "Клиент");
        var allocation = send("POST", base(ctx) + "/expected-payments/" + planId + "/allocations", ctx.owner(),
                "{\"paymentId\":\"" + pay + "\",\"amount\":\"100.00\"}");
        assertThat(allocation.status()).isEqualTo(201);
        String allocationId = allocation.body().path("id").asText();
        assertThat(send("GET", base(ctx) + "/expected-payments/" + planId, ctx.owner(), null).body()
                .path("status").asText()).isEqualTo("SETTLED");

        var deleted = send("DELETE", base(ctx) + "/expected-payments/" + planId + "/allocations/" + allocationId,
                ctx.owner(), null);
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(send("GET", base(ctx) + "/expected-payments/" + planId, ctx.owner(), null).body()
                .path("status").asText()).isEqualTo("OPEN");
        assertThat(send("DELETE", base(ctx) + "/expected-payments/" + planId + "/allocations/" + allocationId,
                ctx.owner(), null).status()).isEqualTo(204);

        assertThat(send("DELETE", base(ctx) + "/expected-payments/" + UUID.randomUUID() + "/allocations/"
                + allocationId, ctx.owner(), null).status()).isEqualTo(404);
        assertThat(send("DELETE", base(ctx) + "/expected-payments/" + planId + "/allocations/"
                + UUID.randomUUID(), ctx.owner(), null).status()).isEqualTo(404);

        UUID viewer = UUID.randomUUID();
        addMember(ctx.owner(), ctx.company(), viewer, "VIEWER");
        assertThat(send("DELETE", base(ctx) + "/expected-payments/" + planId + "/allocations/" + allocationId,
                viewer, null).status()).isEqualTo(403);
        assertThat(send("DELETE", base(ctx) + "/expected-payments/" + planId + "/allocations/" + allocationId,
                null, null).status()).isEqualTo(401);
    }

    private Ctx ctx() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Публичное API");
        return new Ctx(owner, company, createAccount(owner, company, null));
    }

    private UUID payment(Ctx ctx, String date, String amount, Direction direction, String purpose) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        ledger.insertPayment(new Payment(id, ctx.company(), ctx.account(), null, null, null,
                LocalDate.parse(date), new BigDecimal(amount), direction, null, null, null, purpose, "OTHER",
                CategorySource.OTHER, null, null, 0, now, now));
        return id;
    }

    private void observation(Ctx ctx, String date, String status, int paymentCount, boolean mismatch) {
        jdbc.sql("insert into daily_observations (company_id, account_id, date, inflow, outflow, eod, status, "
                        + "payment_count, balance_mismatch, input_version) values (:company, :account, :date, "
                        + ":inflow, :outflow, :eod, :status, :count, :mismatch, 0)")
                .param("company", ctx.company()).param("account", ctx.account()).param("date", LocalDate.parse(date))
                .param("inflow", status.equals("OBSERVED") ? new BigDecimal("10.00") : null)
                .param("outflow", status.equals("OBSERVED") ? new BigDecimal("0.00") : null)
                .param("eod", status.equals("OBSERVED") ? new BigDecimal("10.00") : null)
                .param("status", status).param("count", paymentCount).param("mismatch", mismatch).update();
    }

    private static String range(String from, String to) {
        return "{\"fromDate\":\"" + from + "\",\"toDate\":\"" + to + "\",\"completeness\":\"CONFIRMED_COMPLETE\","
                + "\"reason\":\"выписка\"}";
    }

    private static String plan(String amount) {
        return "{\"title\":\"План\",\"direction\":\"IN\",\"amount\":\"" + amount + "\",\"dueDate\":\"2026-11-15\","
                + "\"categoryCode\":\"REVENUE_CUSTOMERS\",\"relation\":\"ADDITIONAL\",\"source\":\"MANUAL\"}";
    }

    private static String importBody(String source, String... items) {
        return "{\"externalSource\":\"" + source + "\",\"items\":[" + String.join(",", items) + "]}";
    }

    private static String importItem(UUID account, String externalId, String title, String amount,
                                     Long expectedVersion) {
        return "{\"accountId\":\"" + account + "\",\"title\":\"" + title + "\",\"direction\":\"OUT\","
                + "\"amount\":\"" + amount + "\",\"dueDate\":\"2026-11-01\",\"categoryCode\":\"SUPPLIERS\","
                + "\"counterpartyKey\":\"inn:0012345678\",\"note\":null,\"relation\":\"ADDITIONAL\","
                + "\"recurringPatternId\":null,\"baselineCounterpartyKey\":null,\"source\":\"INVOICE\","
                + "\"sourceDocument\":{\"externalSystem\":\"1C\",\"externalId\":\"" + externalId + "\","
                + "\"documentNumber\":\"1\",\"issuedDate\":\"2026-10-01\"}, \"expectedVersion\":"
                + (expectedVersion == null ? "null" : expectedVersion) + "}";
    }

    private static String base(Ctx ctx) {
        return BASE + "/" + ctx.company() + "/accounts/" + ctx.account();
    }

    private record Ctx(UUID owner, UUID company, UUID account) {
    }
}
