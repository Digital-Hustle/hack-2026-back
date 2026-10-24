package ru.cashflow.orchestrator.integrations;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class ImportIntegrationTest extends AbstractIntegrationTest {

    private static final String FINGERPRINT = "be73cdf2560e5ddb65d6c78a0aa5cc104509cc9ff4c1e13270e65b83a6ec8b64";

    @Test
    void stagedRowsAreInvisibleUntilAtomicFinalize() throws Exception {
        var ctx = account("C001");
        var staged = stage(ctx, "import-key-0001", statement("C001", op("C001-000001", "2026-09-01", "990.00", "OUT",
                "BANK_FEES", "RULE", "1244821.98")));

        assertThat(staged.status()).isEqualTo(201);
        assertThat(staged.body().path("status").asText()).isEqualTo("STAGED");
        assertThat(paymentCount()).isZero();
        assertThat(dataVersion(ctx)).isZero();

        var finalized = finalize(ctx, staged.body().path("id").asText(), 0);
        assertThat(finalized.status()).isEqualTo(200);
        assertThat(finalized.body().path("status").asText()).isEqualTo("FINALIZED");
        assertThat(finalized.body().path("rowsInserted").asInt()).isEqualTo(1);
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(dataVersion(ctx)).isEqualTo(1);

        var payment = send("GET", base(ctx) + "/payments?from=2026-09-01&to=2026-09-01", ctx.owner(), null)
                .body().path("items").get(0);
        assertThat(payment.path("amount").asText()).isEqualTo("990.00");
        assertThat(payment.path("categoryCode").asText()).isEqualTo("BANK_FEES");
        assertThat(payment.path("categorySource").asText()).isEqualTo("RULE");
        assertThat(payment.path("ruleVersion").asText()).isEqualTo("2026.10-1");
        assertThat(payment.path("counterpartyKey").asText()).isEqualTo("inn:0068275202");
        var day = send("GET", base(ctx) + "/daily-history?from=2026-09-01&to=2026-09-01", ctx.owner(), null)
                .body().path("items").get(0);
        assertThat(day.path("status").asText()).isEqualTo("OBSERVED");
        assertThat(day.path("balanceEod").asText()).isEqualTo("1244821.98");
    }

    @Test
    void repeatedFinalizeAndReimportNeverCreateFactTwice() throws Exception {
        var ctx = account("C001");
        String body = statement("C001", op("C001-000001", "2026-09-01", "10.00", "IN", "REVENUE_CUSTOMERS", "RULE", null));
        String id = stage(ctx, "import-key-0002", body).body().path("id").asText();

        assertThat(finalize(ctx, id, 0).status()).isEqualTo(200);
        var again = finalize(ctx, id, 0);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().path("rowsInserted").asInt()).isEqualTo(1);
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(dataVersion(ctx)).isEqualTo(1);

        String second = stage(ctx, "import-key-0003", body).body().path("id").asText();
        var reimport = finalize(ctx, second, 0);
        assertThat(reimport.body().path("rowsInserted").asInt()).isZero();
        assertThat(reimport.body().path("rowsAlreadyPresent").asInt()).isEqualTo(1);
        assertThat(paymentCount()).isEqualTo(1);
    }

    @Test
    void idempotencyKeyReturnsSameImportOrConflictsOnDifferentBody() throws Exception {
        var ctx = account("C001");
        String a = statement("C001", op("C001-000001", "2026-09-01", "10.00", "IN", "OTHER", "UNCLASSIFIED", null));
        String b = statement("C001", op("C001-000001", "2026-09-01", "11.00", "IN", "OTHER", "UNCLASSIFIED", null));

        var first = stage(ctx, "import-key-0004", a);
        var replay = stage(ctx, "import-key-0004", a);
        assertThat(replay.body().path("id").asText()).isEqualTo(first.body().path("id").asText());
        assertThat(stage(ctx, "import-key-0004", b).status()).isEqualTo(409);
        assertThat(jdbc.sql("select count(*) from imports").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void csvCompanyIdMustMatchAccountAndDoesNotGrantAccess() throws Exception {
        var ctx = account("C001");
        var stranger = UUID.randomUUID();
        String foreign = statement("C002", op("C002-000001", "2026-09-01", "10.00", "IN", "OTHER", "UNCLASSIFIED", null));

        assertThat(stage(ctx, "import-key-0005", foreign).status()).isEqualTo(422);
        assertThat(send("POST", base(ctx) + "/imports", stranger, statement("C001",
                op("C001-1", "2026-09-01", "1.00", "IN", "OTHER", "UNCLASSIFIED", null)), "Idempotency-Key",
                "import-key-0006").status()).isEqualTo(404);
        assertThat(paymentCount()).isZero();
    }

    @Test
    void viewerCannotImportAndOpenDayOrInvalidMoneyIsRejected() throws Exception {
        var ctx = account("C001");
        UUID viewer = UUID.randomUUID();
        addMember(ctx.owner(), ctx.company(), viewer, "VIEWER");
        String ok = statement("C001", op("C001-1", "2026-09-01", "1.00", "IN", "OTHER", "UNCLASSIFIED", null));

        assertThat(send("POST", base(ctx) + "/imports", viewer, ok, "Idempotency-Key", "import-key-0007").status())
                .isEqualTo(403);
        assertThat(stage(ctx, "import-key-0008", statement("C001",
                op("C001-1", "2099-01-01", "1.00", "IN", "OTHER", "UNCLASSIFIED", null))).status()).isEqualTo(422);
        assertThat(stage(ctx, "import-key-0009", statement("C001",
                op("C001-1", "2026-09-01", "1.001", "IN", "OTHER", "UNCLASSIFIED", null))).status()).isEqualTo(422);
        assertThat(stage(ctx, "import-key-0010", statement("C001",
                op("C001-1", "2026-09-01", "-1.00", "IN", "OTHER", "UNCLASSIFIED", null))).status()).isEqualTo(422);
        assertThat(send("POST", base(ctx) + "/imports", ctx.owner(), ok).status()).isEqualTo(428);
    }

    @Test
    void cancelledImportCannotBeFinalized() throws Exception {
        var ctx = account("C001");
        String id = stage(ctx, "import-key-0011", statement("C001",
                op("C001-1", "2026-09-01", "1.00", "IN", "OTHER", "UNCLASSIFIED", null))).body().path("id").asText();

        assertThat(send("POST", base(ctx) + "/imports/" + id + "/cancel", ctx.owner(), null).status()).isEqualTo(200);
        assertThat(finalize(ctx, id, 1).status()).isEqualTo(409);
        assertThat(paymentCount()).isZero();
    }

    @Test
    void gapsLongerThanAWeekStayUnknownInHistory() throws Exception {
        var ctx = account("C001");
        String body = statement("C001",
                op("C001-1", "2026-09-01", "1.00", "IN", "OTHER", "UNCLASSIFIED", "100.00") + ","
                        + op("C001-2", "2026-09-20", "1.00", "IN", "OTHER", "UNCLASSIFIED", "101.00"));
        String id = stage(ctx, "import-key-0012", body).body().path("id").asText();
        finalize(ctx, id, 0);

        var days = send("GET", base(ctx) + "/daily-history?from=2026-09-01&to=2026-09-20", ctx.owner(), null)
                .body().path("items");
        assertThat(days).hasSize(20);
        assertThat(days.get(0).path("status").asText()).isEqualTo("OBSERVED");
        assertThat(days.get(5).path("status").asText()).isEqualTo("MISSING");
        assertThat(days.get(19).path("status").asText()).isEqualTo("OBSERVED");
    }

    private Ctx account(String external) throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Компания");
        return new Ctx(owner, company, createAccount(owner, company, external));
    }

    private Response stage(Ctx ctx, String key, String body) throws Exception {
        return send("POST", base(ctx) + "/imports", ctx.owner(), body, "Idempotency-Key", key);
    }

    private Response finalize(Ctx ctx, String id, long version) throws Exception {
        return send("POST", base(ctx) + "/imports/" + id + "/finalize", ctx.owner(), null, "If-Match", "\"" + version + "\"");
    }

    private long paymentCount() {
        return jdbc.sql("select count(*) from payments").query(Long.class).single();
    }

    private long dataVersion(Ctx ctx) throws Exception {
        return send("GET", "/api/v1/orchestrator/companies/" + ctx.company(), ctx.owner(), null).body()
                .path("dataVersion").asLong();
    }

    private static String base(Ctx ctx) {
        return "/api/v1/orchestrator/companies/" + ctx.company() + "/accounts/" + ctx.account();
    }

    /** Same field shape as PreProcessor PreparedStatementRs, schemaVersion 1; dailyBalances derived like PreProcessor. */
    private static String statement(String company, String operations) {
        var days = new StringBuilder();
        var matcher = java.util.regex.Pattern.compile(
                "\"operationDate\":\"([0-9-]+)\".*?\"balanceEod\":(null|\"[0-9.-]+\")").matcher(operations);
        var seen = new java.util.HashSet<String>();
        while (matcher.find()) {
            if (!"null".equals(matcher.group(2)) && seen.add(matcher.group(1))) {
                days.append(days.isEmpty() ? "" : ",").append("{\"companyId\":\"").append(company)
                        .append("\",\"date\":\"").append(matcher.group(1)).append("\",\"inflow\":\"0.00\",")
                        .append("\"outflow\":\"0.00\",\"balanceEod\":").append(matcher.group(2))
                        .append(",\"balanceStatus\":\"OBSERVED\",\"reconcilesWithPrevious\":null}");
            }
        }
        return "{\"schemaVersion\":\"1\",\"rulesVersion\":\"2026.10-1\",\"inputFingerprint\":\"" + FINGERPRINT + "\","
                + "\"preparedAt\":\"2026-10-23T22:56:43Z\",\"companies\":[{\"companyId\":\"" + company + "\"}],"
                + "\"operations\":[" + operations + "],\"dailyBalances\":[" + days + "],"
                + "\"report\":{\"status\":\"PREPARED\",\"rowsTotal\":1,\"issueCount\":0,\"issues\":[]}}";
    }

    private static String op(String id, String date, String amount, String direction, String category, String source,
                             String eod) {
        String company = id.substring(0, id.indexOf('-'));
        return "{\"companyId\":\"" + company + "\",\"operationId\":\"" + id + "\",\"operationDate\":\"" + date + "\","
                + "\"amount\":\"" + amount + "\",\"direction\":\"" + direction + "\",\"counterpartyInn\":\"0068275202\","
                + "\"counterpartyName\":\"ПАО «Банк»\",\"paymentPurpose\":\"Назначение\","
                + "\"balanceEod\":" + (eod == null ? "null" : "\"" + eod + "\"") + ","
                + "\"category\":{\"code\":\"" + category + "\",\"title\":\"t\",\"source\":\"" + source + "\","
                + "\"ruleId\":null,\"rulesVersion\":\"2026.10-1\",\"needsReview\":false},"
                + "\"sourceRecord\":1,\"sourceLine\":2,\"sourceFields\":{}}";
    }

    private record Ctx(UUID owner, UUID company, UUID account) {
    }
}
