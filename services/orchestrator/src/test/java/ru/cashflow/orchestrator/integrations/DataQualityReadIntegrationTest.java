package ru.cashflow.orchestrator.integrations;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class DataQualityReadIntegrationTest extends AbstractIntegrationTest {

    @Test
    void emptyHistoryIsInsufficientAndMissingDaysAreNotZeros() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Качество");
        UUID account = createAccount(owner, company, null);
        LocalDate lastDay = companyToday(company).minusDays(1);
        LocalDate firstDay = lastDay.minusDays(6);
        String base = base(company, account);

        var report = send("GET", base + "/data-quality?from=" + firstDay + "&to=" + lastDay, owner, null);
        assertThat(report.status()).as(report.raw()).isEqualTo(200);
        assertThat(report.body().path("accountId").asText()).isEqualTo(account.toString());
        assertThat(report.body().path("fromDate").asText()).isEqualTo(firstDay.toString());
        assertThat(report.body().path("quality").asText()).isEqualTo("INSUFFICIENT");
        assertThat(report.body().path("completeDayCount").asInt()).isZero();
        assertThat(report.body().path("missingDayCount").asInt()).isEqualTo(7);
        assertThat(report.body().path("balanceMismatchCount").asInt()).isZero();
        assertThat(report.body().path("emptyPurposeCount").asLong()).isZero();
        assertThat(report.body().path("warnings").findValuesAsText("code")).contains("MISSING_DAYS");
        assertThat(report.body().path("warnings").get(0).path("date").isNull()).isTrue();

        var defaultReport = send("GET", base + "/data-quality", owner, null);
        assertThat(defaultReport.status()).isEqualTo(200);
        assertThat(defaultReport.body().path("missingDayCount").asInt()).isEqualTo(182);
        assertThat(defaultReport.body().path("toDate").asText()).isEqualTo(lastDay.toString());
    }

    @Test
    void fullHistoryAndOverlappingRangesAreReportedWithoutDiscardingAuditHistory() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Полнота");
        UUID account = createAccount(owner, company, null);
        LocalDate to = companyToday(company).minusDays(1);
        LocalDate from = to.minusDays(6);
        String base = base(company, account);

        assertThat(completeness(base, owner, from, to, "CONFIRMED_COMPLETE").status()).isEqualTo(201);
        var full = send("GET", base + "/data-quality?from=" + from + "&to=" + to, owner, null);
        assertThat(full.status()).as(full.raw()).isEqualTo(200);
        assertThat(full.body().path("quality").asText()).isEqualTo("GOOD");
        assertThat(full.body().path("completeDayCount").asInt()).isEqualTo(7);
        assertThat(full.body().path("missingDayCount").asInt()).isZero();

        assertThat(completeness(base, owner, to, to, "UNKNOWN").status()).isEqualTo(201);
        var partial = send("GET", base + "/data-quality?from=" + from + "&to=" + to, owner, null);
        assertThat(partial.body().path("quality").asText()).isEqualTo("INSUFFICIENT");
        assertThat(partial.body().path("completeDayCount").asInt()).isEqualTo(6);
        assertThat(partial.body().path("missingDayCount").asInt()).isEqualTo(1);

        var firstPage = send("GET", base + "/completeness-ranges?page=0&size=1", owner, null);
        assertThat(firstPage.status()).as(firstPage.raw()).isEqualTo(200);
        assertThat(firstPage.body().path("page").path("total").asLong()).isEqualTo(2);
        assertThat(firstPage.body().path("page").path("hasNext").asBoolean()).isTrue();
        assertThat(firstPage.body().path("items")).hasSize(1);
        assertThat(firstPage.body().path("items").get(0).path("completeness").asText()).isEqualTo("UNKNOWN");
        assertThat(firstPage.body().path("items").get(0).path("createdAt").isTextual()).isTrue();

        var secondPage = send("GET", base + "/completeness-ranges?page=1&size=1", owner, null);
        assertThat(secondPage.body().path("page").path("hasNext").asBoolean()).isFalse();
        assertThat(secondPage.body().path("items").get(0).path("completeness").asText())
                .isEqualTo("CONFIRMED_COMPLETE");
    }

    @Test
    void viewerCanReadButStrangersAndForeignAccountsCannot() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        UUID company = createCompany(owner, "Права");
        UUID account = createAccount(owner, company, null);
        addMember(owner, company, viewer, "VIEWER");
        String base = base(company, account);

        for (String suffix : new String[] {"/data-quality", "/completeness-ranges"}) {
            assertThat(send("GET", base + suffix, viewer, null).status()).isEqualTo(200);
            assertThat(send("GET", base + suffix, UUID.randomUUID(), null).status()).isEqualTo(404);
            assertThat(send("GET", base.replace(account.toString(), UUID.randomUUID().toString())
                    + suffix, owner, null).status()).isEqualTo(404);
        }
    }

    @Test
    void rejectsOpenOrReversedQualityWindowsAndInvalidPaging() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Валидация");
        UUID account = createAccount(owner, company, null);
        LocalDate today = companyToday(company);
        String base = base(company, account);

        assertThat(send("GET", base + "/data-quality?from=" + today.minusDays(1) + "&to=" + today,
                owner, null).status()).isEqualTo(422);
        assertThat(send("GET", base + "/data-quality?from=" + today.minusDays(1),
                owner, null).status()).isEqualTo(422);
        assertThat(send("GET", base + "/data-quality?from=" + today.minusDays(1)
                + "&to=" + today.minusDays(5), owner, null).status()).isEqualTo(422);
        assertThat(send("GET", base + "/completeness-ranges?page=-1&size=100", owner, null)
                .status()).isEqualTo(422);
        assertThat(send("GET", base + "/completeness-ranges?page=0&size=201", owner, null)
                .status()).isEqualTo(422);
    }

    @Test
    void warningsCountEmptyPurposesAndBalanceMismatchesFromActualData() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Проблемы истории");
        UUID account = createAccount(owner, company, null);
        LocalDate to = companyToday(company).minusDays(1);
        LocalDate from = to.minusDays(6);
        String base = base(company, account);

        assertThat(completeness(base, owner, from, to, "CONFIRMED_COMPLETE").status()).isEqualTo(201);
        jdbc.sql("insert into payments(id,company_id,account_id,operation_date,amount,direction,"
                        + "category_code,category_source,known_at,created_at,source,purpose) "
                        + "values(:id,:company,:account,:date,100,'IN','OTHER','OTHER',now(),now(),'IMPORT',null)")
                .param("id", UUID.randomUUID()).param("company", company).param("account", account)
                .param("date", from.plusDays(1)).update();
        jdbc.sql("update daily_observations set balance_mismatch=true "
                        + "where company_id=:company and account_id=:account and date=:date")
                .param("company", company).param("account", account).param("date", from.plusDays(2)).update();

        var report = send("GET", base + "/data-quality?from=" + from + "&to=" + to, owner, null);
        assertThat(report.status()).as(report.raw()).isEqualTo(200);
        assertThat(report.body().path("balanceMismatchCount").asInt()).isEqualTo(1);
        assertThat(report.body().path("emptyPurposeCount").asLong()).isEqualTo(1);
        assertThat(report.body().path("warnings").findValuesAsText("code"))
                .contains("EMPTY_PURPOSE", "BALANCE_MISMATCH");
    }

    private Response completeness(String base, UUID user, LocalDate from, LocalDate to, String status)
            throws Exception {
        return send("POST", base + "/completeness", user, "{\"fromDate\":\"" + from
                + "\",\"toDate\":\"" + to + "\",\"completeness\":\"" + status
                + "\",\"reason\":\"тест\"}");
    }

    private static String base(UUID company, UUID account) {
        return "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;
    }
}
