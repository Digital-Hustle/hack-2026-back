package ru.cashflow.orchestrator.integrations;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class ImportIntegrationTest extends AbstractIntegrationTest {

    private static final String FINGERPRINT = "be73cdf2560e5ddb65d6c78a0aa5cc104509cc9ff4c1e13270e65b83a6ec8b64";

    @Test
    void importWarnsWhenAnImportedOperationLooksLikeAnExistingManualFact() throws Exception {
        var ctx = account("C002");
        var manual = send("POST", "/api/v1/orchestrator/companies/" + ctx.company() + "/payments", ctx.owner(),
                "{\"accountId\":\"" + ctx.account() + "\",\"operationDate\":\"2026-09-01\",\"amount\":\"990.00\","
                        + "\"direction\":\"OUT\",\"counterpartyInn\":\"0068275202\",\"counterpartyName\":\"ПАО «Банк»\","
                        + "\"purpose\":\"Назначение\",\"categoryCode\":\"BANK_FEES\",\"note\":\"касса\"}",
                "Idempotency-Key", "manual-import-duplicate");
        assertThat(manual.status()).as(manual.raw()).isEqualTo(201);

        var staged = send("POST", base(ctx) + "/imports?fileName=statement.csv&fileSha256=" + FINGERPRINT,
                ctx.owner(), statement("C002", op("C002-000001", "2026-09-01", "990.00", "OUT",
                        "BANK_FEES", "RULE", null)), "Idempotency-Key", "import-key-manual-duplicate");
        assertThat(staged.status()).as(staged.raw()).isEqualTo(201);
        assertThat(staged.body().path("warningCount").asInt()).isEqualTo(1);

        var issues = send("GET", "/api/v1/orchestrator/companies/" + ctx.company() + "/imports/"
                + staged.body().path("id").asText() + "/issues?severity=WARNING", ctx.owner(), null);
        assertThat(issues.body().path("items")).hasSize(1);
        assertThat(issues.body().path("items").get(0).path("code").asText()).isEqualTo("POSSIBLE_DUPLICATE_MANUAL");
    }

    @Test
    void completenessDefaultsToConfirmedForTheOperationsPeriodWithoutFlagsOrDates() throws Exception {
        var ctx = account("C001");
        String operations = op("C001-000001", "2026-09-01", "990.00", "OUT", "BANK_FEES", "RULE", "1000.00") + ","
                + op("C001-000002", "2026-09-04", "10.00", "OUT", "BANK_FEES", "RULE", "990.00");

        var staged = send("POST", base(ctx) + "/imports", ctx.owner(), statement("C001", operations),
                "Idempotency-Key", "import-key-auto1");
        assertThat(staged.status()).isEqualTo(201);
        assertThat(staged.body().path("fromDate").asText()).isEqualTo("2026-09-01");
        assertThat(staged.body().path("toDate").asText()).isEqualTo("2026-09-04");
        assertThat(finalize(ctx, staged.body().path("id").asText(), 0).status()).isEqualTo(200);

        var days = send("GET", base(ctx) + "/daily-history?from=2026-09-01&to=2026-09-04", ctx.owner(), null)
                .body().path("items");
        assertThat(days.get(0).path("status").asText()).isEqualTo("OBSERVED");
        assertThat(days.get(1).path("status").asText()).isEqualTo("ZERO_CONFIRMED");
        assertThat(days.get(2).path("status").asText()).isEqualTo("ZERO_CONFIRMED");
        assertThat(days.get(3).path("status").asText()).isEqualTo("OBSERVED");
    }

    @Test
    void explicitUnknownStillLeavesDaysAsGaps() throws Exception {
        var ctx = account("C001");
        var staged = send("POST", base(ctx) + "/imports?completeness=UNKNOWN", ctx.owner(),
                statement("C001", op("C001-000001", "2026-09-01", "990.00", "OUT", "BANK_FEES", "RULE", "1000.00")),
                "Idempotency-Key", "import-key-auto2");
        assertThat(staged.status()).isEqualTo(201);
        assertThat(finalize(ctx, staged.body().path("id").asText(), 0).status()).isEqualTo(200);
        var days = send("GET", base(ctx) + "/daily-history?from=2026-09-01&to=2026-09-01", ctx.owner(), null)
                .body().path("items");
        assertThat(days.get(0).path("status").asText()).isEqualTo("MISSING");
    }

    @Test
    void stagedRowsAreInvisibleUntilAtomicFinalize() throws Exception {
        var ctx = account("C001");
        var staged = send("POST", base(ctx) + "/imports?completeness=CONFIRMED_COMPLETE"
                + "&declaredFromDate=2026-09-01&declaredToDate=2026-09-01",
                ctx.owner(), statement("C001", op("C001-000001", "2026-09-01", "990.00", "OUT",
                "BANK_FEES", "RULE", "1244821.98")), "Idempotency-Key", "import-key-0001");

        assertThat(staged.status()).isEqualTo(201);
        assertThat(staged.body().path("status").asText()).isEqualTo("STAGING");
        assertThat(staged.body().path("companyId").asText()).isEqualTo(ctx.company().toString());
        assertThat(staged.body().path("accountId").asText()).isEqualTo(ctx.account().toString());
        assertThat(staged.body().path("fileName").asText()).isEqualTo("statement.csv");
        assertThat(staged.body().path("fileSha256").asText()).isEqualTo(FINGERPRINT);
        assertThat(staged.body().path("totalRows").asInt()).isEqualTo(1);
        assertThat(staged.body().path("acceptedRows").asInt()).isEqualTo(1);
        assertThat(staged.body().path("rejectedRows").asInt()).isZero();
        assertThat(staged.body().path("duplicateRows").asInt()).isZero();
        assertThat(staged.body().path("warningCount").asInt()).isZero();
        assertThat(staged.body().path("fromDate").asText()).isEqualTo("2026-09-01");
        assertThat(staged.body().path("toDate").asText()).isEqualTo("2026-09-01");
        assertThat(staged.body().path("dataVersion").isNull()).isTrue();
        assertThat(paymentCount()).isZero();
        assertThat(dataVersion(ctx)).isZero();

        var finalized = finalize(ctx, staged.body().path("id").asText(), 0);
        assertThat(finalized.status()).isEqualTo(200);
        assertThat(finalized.body().path("status").asText()).isEqualTo("COMMITTED");
        assertThat(finalized.body().path("dataVersion").asLong()).isEqualTo(1);
        assertThat(finalized.body().path("completedAt").isNull()).isFalse();
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(dataVersion(ctx)).isEqualTo(1);

        var payment = send("GET", base(ctx) + "/payments?from=2026-09-01&to=2026-09-01", ctx.owner(), null)
                .body().path("items").get(0);
        assertThat(payment.path("amount").asText()).isEqualTo("990.00");
        assertThat(payment.path("source").asText()).isEqualTo("IMPORT");
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
        assertThat(again.body().path("status").asText()).isEqualTo("COMMITTED");
        assertThat(again.body().path("acceptedRows").asInt()).isEqualTo(1);
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(dataVersion(ctx)).isEqualTo(1);

        String second = stage(ctx, "import-key-0003", body).body().path("id").asText();
        var reimport = finalize(ctx, second, 0);
        assertThat(reimport.body().path("status").asText()).isEqualTo("COMMITTED");
        assertThat(reimport.body().path("acceptedRows").asInt()).isZero();
        assertThat(reimport.body().path("duplicateRows").asInt()).isEqualTo(1);
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
    void atomicRejectionReturnsProblemWithImportResourceIdAndReadableReport() throws Exception {
        var ctx = account("C001");
        String invalid = statement("C001", op("C001-1", "2026-09-01", "1.00", "IN", "OTHER", "UNCLASSIFIED", null))
                .replace("\"issueCount\":0,\"issues\":[]",
                        "\"rowsAccepted\":0,\"rowsRejected\":1,\"rowsDuplicate\":0,"
                                + "\"issueCount\":1,\"issues\":[{\"code\":\"BAD_ROW\",\"severity\":\"ERROR\","
                                + "\"sourceLine\":2,\"field\":\"amount\",\"message\":\"bad amount\"}]");
        var rejected = send("POST", base(ctx) + "/imports?fileName=statement.csv&fileSha256=" + FINGERPRINT
                        + "&validationMode=ATOMIC&completeness=CONFIRMED_COMPLETE&declaredFromDate=2026-09-01"
                        + "&declaredToDate=2026-09-30",
                ctx.owner(), invalid, "Idempotency-Key", "import-key-atomic");
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.body().path("code").asText()).isEqualTo("IMPORT_REJECTED");
        assertThat(rejected.body().path("resourceId").isTextual()).isTrue();
        assertThat(paymentCount()).isZero();

        UUID importId = UUID.fromString(rejected.body().path("resourceId").asText());
        var report = send("GET", "/api/v1/orchestrator/companies/" + ctx.company() + "/imports/" + importId,
                ctx.owner(), null);
        assertThat(report.status()).isEqualTo(200);
        assertThat(report.body().path("status").asText()).isEqualTo("REJECTED");
        assertThat(report.body().path("totalRows").asInt()).isEqualTo(1);
        assertThat(report.body().path("acceptedRows").asInt()).isZero();
        assertThat(report.body().path("rejectedRows").asInt()).isEqualTo(1);
        assertThat(report.body().path("warningCount").asInt()).isZero();
        assertThat(report.body().path("fromDate").asText()).isEqualTo("2026-09-01");
        assertThat(report.body().path("toDate").asText()).isEqualTo("2026-09-30");
        assertThat(report.body().path("completedAt").isNull()).isFalse();

        var issues = send("GET", "/api/v1/orchestrator/companies/" + ctx.company() + "/imports/" + importId
                + "/issues?page=0&size=20", ctx.owner(), null);
        assertThat(issues.status()).isEqualTo(200);
        assertThat(issues.body().path("items")).hasSize(1);
        assertThat(issues.body().path("items").get(0).path("id").asText()).isNotBlank();
        assertThat(issues.body().path("items").get(0).path("severity").asText()).isEqualTo("ERROR");
        assertThat(issues.body().path("items").get(0).path("rowNumber").asInt()).isEqualTo(2);
        assertThat(issues.body().path("page").path("total").asLong()).isEqualTo(1);
    }

    @Test
    void importListIsPagedWithFileMetadata() throws Exception {
        var ctx = account("C001");
        stage(ctx, "import-key-list-1", statement("C001",
                op("C001-1", "2026-09-01", "1.00", "IN", "OTHER", "UNCLASSIFIED", null)));
        stage(ctx, "import-key-list-2", statement("C001",
                op("C001-2", "2026-09-02", "2.00", "IN", "OTHER", "UNCLASSIFIED", null)));

        var page = send("GET", "/api/v1/orchestrator/companies/" + ctx.company() + "/imports?accountId="
                + ctx.account() + "&page=0&size=1", ctx.owner(), null);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().path("items")).hasSize(1);
        assertThat(page.body().path("page").path("total").asLong()).isEqualTo(2);
        assertThat(page.body().path("page").path("hasNext").asBoolean()).isTrue();
        var item = page.body().path("items").get(0);
        assertThat(item.path("companyId").asText()).isEqualTo(ctx.company().toString());
        assertThat(item.path("accountId").asText()).isEqualTo(ctx.account().toString());
        assertThat(item.path("fileName").asText()).isEqualTo("statement.csv");
        assertThat(item.path("fileSha256").asText()).isEqualTo(FINGERPRINT);
        assertThat(item.path("status").asText()).isEqualTo("STAGING");
    }

    @Test
    void cancelledImportCannotBeFinalized() throws Exception {
        var ctx = account("C001");
        String id = stage(ctx, "import-key-0011", statement("C001",
                op("C001-1", "2026-09-01", "1.00", "IN", "OTHER", "UNCLASSIFIED", null))).body().path("id").asText();

        var cancelled = send("POST", base(ctx) + "/imports/" + id + "/cancel", ctx.owner(), null);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().path("status").asText()).isEqualTo("ABORTED");
        assertThat(finalize(ctx, id, 1).status()).isEqualTo(409);
        assertThat(paymentCount()).isZero();
    }

    @Test
    void withExplicitUnknownCompletenessGapsStayUnknownInHistory() throws Exception {
        var ctx = account("C001");
        String body = statement("C001",
                op("C001-1", "2026-09-01", "1.00", "IN", "OTHER", "UNCLASSIFIED", "100.00") + ","
                        + op("C001-2", "2026-09-20", "1.00", "IN", "OTHER", "UNCLASSIFIED", "101.00"));
        String id = send("POST", base(ctx) + "/imports?completeness=UNKNOWN", ctx.owner(), body,
                "Idempotency-Key", "import-key-0012").body().path("id").asText();
        finalize(ctx, id, 0);

        var days = send("GET", base(ctx) + "/daily-history?from=2026-09-01&to=2026-09-20", ctx.owner(), null)
                .body().path("items");
        assertThat(days).hasSize(20);
        assertThat(days.get(0).path("status").asText()).isEqualTo("MISSING");
        assertThat(days.get(5).path("status").asText()).isEqualTo("MISSING");
        assertThat(days.get(19).path("status").asText()).isEqualTo("MISSING");
    }

    @Test
    void reimportIgnoresPreparedTimestampAndPreservesDataVersion() throws Exception {
        var ctx = account("C001");
        String body = statement("C001", op("C001-000001", "2026-09-01", "10.00", "IN",
                "OTHER", "UNCLASSIFIED", null));
        String url = base(ctx) + "/imports?fileName=C001.csv&fileSha256=" + "a".repeat(64)
                + "&validationMode=SKIP_INVALID&completeness=UNKNOWN";

        var first = send("POST", url, ctx.owner(), body, "Idempotency-Key", "replay-stable-001");
        assertThat(first.status()).isEqualTo(201);
        String id = first.body().path("id").asText();
        finalize(ctx, id, 0);

        String again = body.replace("2026-10-23T22:56:43Z", "2026-10-24T12:00:00Z");
        var replay = send("POST", url, ctx.owner(), again, "Idempotency-Key", "replay-stable-001");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().path("id").asText()).isEqualTo(id);
        assertThat(replay.body().path("dataVersion").asLong()).isEqualTo(1);
        assertThat(paymentCount()).isEqualTo(1);
        assertThat(dataVersion(ctx)).isEqualTo(1);
    }

    @Test
    void atomicRejectsPartialFileWithoutCommittingFinancialData() throws Exception {
        var ctx = account("C001");
        String body = statement("C001", op("C001-000001", "2026-09-01", "10.00", "IN",
                "OTHER", "UNCLASSIFIED", null));
        body = body.replace("\"status\":\"PREPARED\"", "\"status\":\"PARTIAL\"")
                .replace("\"rowsTotal\":1", "\"rowsTotal\":2,\"rowsRejected\":1");
        var result = send("POST", base(ctx) + "/imports?validationMode=ATOMIC", ctx.owner(), body,
                "Idempotency-Key", "atomic-reject-001");
        assertThat(result.status()).isEqualTo(422);
        assertThat(paymentCount()).isZero();
        assertThat(jdbc.sql("select count(*) from imports where status = 'REJECTED'").query(Long.class).single())
                .isEqualTo(1);
        assertThat(result.body().path("resourceId").isTextual()).isTrue();
        assertThat(dataVersion(ctx)).isZero();
        assertThat(jdbc.sql("select count(*) from completeness_ranges").query(Long.class).single()).isZero();
    }

    @Test
    void partialImportCannotConfirmCoverageButCanBeRetriedAsUnknown() throws Exception {
        var ctx = account("C001");
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(statement("C001",
                op("C001-1", "2026-09-15", "100.00", "IN", "OTHER", "UNCLASSIFIED", "1000.00") + ","
                        + op("C001-2", "2026-09-17", "50.00", "IN", "OTHER", "UNCLASSIFIED", "1050.00")));
        var report = (com.fasterxml.jackson.databind.node.ObjectNode) payload.path("report");
        report.put("status", "PARTIAL").put("rowsTotal", 3).put("rowsAccepted", 2)
                .put("rowsRejected", 1).put("issueCount", 1);
        report.withArray("issues").addObject().put("code", "INVALID_FIELD").put("severity", "ERROR")
                .put("sourceLine", 3).put("field", "amount").put("message", "Некорректная сумма");
        String url = base(ctx) + "/imports?fileName=partial.csv&fileSha256=" + "a".repeat(64)
                + "&validationMode=SKIP_INVALID&completeness=CONFIRMED_COMPLETE"
                + "&declaredFromDate=2026-09-01&declaredToDate=2026-09-30";
        String key = "partial-coverage-001";

        var rejected = send("POST", url, ctx.owner(), payload.toString(), "Idempotency-Key", key);
        assertThat(rejected.status()).as(rejected.raw()).isEqualTo(422);
        assertThat(rejected.body().path("code").asText()).isEqualTo("INCOMPLETE_STATEMENT");
        assertThat(jdbc.sql("select count(*) from imports").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from completeness_ranges").query(Long.class).single()).isZero();
        assertThat(paymentCount()).isZero();
        assertThat(dataVersion(ctx)).isZero();

        String unknownUrl = base(ctx) + "/imports?fileName=partial.csv&fileSha256=" + "a".repeat(64)
                + "&validationMode=SKIP_INVALID&completeness=UNKNOWN";
        var staged = send("POST", unknownUrl, ctx.owner(), payload.toString(), "Idempotency-Key", key);
        assertThat(staged.status()).as(staged.raw()).isEqualTo(201);
        var finalized = finalize(ctx, staged.body().path("id").asText(), 0);
        assertThat(finalized.status()).as(finalized.raw()).isEqualTo(200);
        assertThat(finalized.body().path("acceptedRows").asInt()).isEqualTo(2);
        assertThat(finalized.body().path("rejectedRows").asInt()).isEqualTo(1);
        assertThat(paymentCount()).isEqualTo(2);
        assertThat(dataVersion(ctx)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from completeness_ranges").query(Long.class).single()).isZero();
        var days = send("GET", base(ctx) + "/daily-history?from=2026-09-15&to=2026-09-17", ctx.owner(), null)
                .body().path("items");
        assertThat(days).hasSize(3);
        assertThat(days.get(1).path("status").asText()).isEqualTo("MISSING");
        assertThat(days.get(1).path("outflow").isNull()).isTrue();
    }

    @Test
    void confirmedCompleteUsesExplicitPeriodAndUnknownDoesNotInventCoverage() throws Exception {
        var ctx = account("C001");
        String body = statement("C001", op("C001-000001", "2026-09-15", "10.00", "IN",
                "OTHER", "UNCLASSIFIED", null));
        String unknown = stage(ctx, "unknown-period-001", body).body().path("id").asText();
        finalize(ctx, unknown, 0);
        long before = jdbc.sql("select count(*) from completeness_ranges where account_id = :account")
                .param("account", ctx.account()).query(Long.class).single();
        assertThat(before).isZero();

        String url = base(ctx) + "/imports?completeness=CONFIRMED_COMPLETE&declaredFromDate=2026-09-01"
                + "&declaredToDate=2026-09-30";
        String id = send("POST", url, ctx.owner(), statement("C001",
                op("C001-000002", "2026-09-15", "11.00", "IN", "OTHER", "UNCLASSIFIED", null)),
                "Idempotency-Key", "complete-period-001").body().path("id").asText();
        var result = finalize(ctx, id, 0);
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().path("dataVersion").asLong()).isEqualTo(2);
        assertThat(jdbc.sql("select from_date::text || '/' || to_date::text from completeness_ranges "
                + "where account_id = :account").param("account", ctx.account()).query(String.class).single())
                .isEqualTo("2026-09-01/2026-09-30");
    }

    @Test
    void duplicateOnlyFinalizationDoesNotBumpDataVersion() throws Exception {
        var ctx = account("C001");
        String body = statement("C001", op("C001-000001", "2026-09-01", "10.00", "IN",
                "OTHER", "UNCLASSIFIED", null));
        String id = stage(ctx, "dup-original-001", body).body().path("id").asText();
        finalize(ctx, id, 0);
        String duplicate = stage(ctx, "dup-repeat-002", body).body().path("id").asText();
        var result = finalize(ctx, duplicate, 0);
        assertThat(result.body().path("acceptedRows").asInt()).isZero();
        assertThat(result.body().path("duplicateRows").asInt()).isEqualTo(1);
        assertThat(result.body().path("dataVersion").asLong()).isEqualTo(1);
        assertThat(dataVersion(ctx)).isEqualTo(1);
    }

    @Test
    void duplicateOnlyConfirmationChangesCoverageOnce() throws Exception {
        var ctx = account("C001");
        String body = statement("C001", op("C001-1", "2026-09-15", "10.00", "IN",
                "OTHER", "UNCLASSIFIED", null));
        String first = stage(ctx, "dup-unknown-100", body).body().path("id").asText();
        finalize(ctx, first, 0);
        assertThat(dataVersion(ctx)).isEqualTo(1);

        String url = base(ctx) + "/imports?completeness=CONFIRMED_COMPLETE"
                + "&declaredFromDate=2026-09-01&declaredToDate=2026-09-30";
        String second = send("POST", url, ctx.owner(), body, "Idempotency-Key", "dup-complete-101")
                .body().path("id").asText();
        var confirmed = finalize(ctx, second, 0);
        assertThat(confirmed.body().path("acceptedRows").asInt()).isZero();
        assertThat(confirmed.body().path("dataVersion").asLong()).isEqualTo(2);
        assertThat(dataVersion(ctx)).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from completeness_ranges where account_id = :account")
                .param("account", ctx.account()).query(Long.class).single()).isEqualTo(1);
        var day = send("GET", base(ctx) + "/daily-history?from=2026-09-15&to=2026-09-15",
                ctx.owner(), null).body().path("items").get(0);
        assertThat(day.path("status").asText()).isEqualTo("OBSERVED");

        String third = send("POST", url, ctx.owner(), body, "Idempotency-Key", "dup-complete-102")
                .body().path("id").asText();
        var repeated = finalize(ctx, third, 0);
        assertThat(repeated.body().path("acceptedRows").asInt()).isZero();
        assertThat(repeated.body().path("dataVersion").asLong()).isEqualTo(2);
        assertThat(dataVersion(ctx)).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from completeness_ranges where account_id = :account")
                .param("account", ctx.account()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void overlappingImportDoesNotOverrideEarlierOrManualBalanceAnchor() throws Exception {
        var ctx = account("C001");
        String firstBody = statement("C001", op("C001-1", "2026-09-15", "10.00", "IN",
                "OTHER", "UNCLASSIFIED", "100.00"));
        String first = send("POST", base(ctx) + "/imports?completeness=CONFIRMED_COMPLETE"
                + "&declaredFromDate=2026-09-15&declaredToDate=2026-09-15",
                ctx.owner(), firstBody, "Idempotency-Key", "anchor-original-100").body().path("id").asText();
        finalize(ctx, first, 0);

        var manual = send("POST", base(ctx) + "/balance-anchors", ctx.owner(),
                "{\"date\":\"2026-09-15\",\"balance\":\"200.00\",\"reason\":\"Verified balance\"}");
        assertThat(manual.status()).isEqualTo(201);
        assertThat(dataVersion(ctx)).isEqualTo(2);

        String secondBody = statement("C001",
                op("C001-1", "2026-09-15", "10.00", "IN", "OTHER", "UNCLASSIFIED", "999.00")
                + "," + op("C001-2", "2026-09-15", "15.00", "IN", "OTHER", "UNCLASSIFIED", "999.00"));
        String second = stage(ctx, "anchor-overlap-101", secondBody).body().path("id").asText();
        finalize(ctx, second, 0);
        assertThat(jdbc.sql("select count(*) from balance_anchors where account_id = :account")
                .param("account", ctx.account()).query(Long.class).single()).isEqualTo(2);
        var day = send("GET", base(ctx) + "/daily-history?from=2026-09-15&to=2026-09-15",
                ctx.owner(), null).body().path("items").get(0);
        assertThat(day.path("balanceEod").asText()).isEqualTo("200.00");
        assertThat(dataVersion(ctx)).isEqualTo(3);
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
