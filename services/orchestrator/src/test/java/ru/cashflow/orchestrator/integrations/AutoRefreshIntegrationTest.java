package ru.cashflow.orchestrator.integrations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.forecast.application.AutoRefreshService;
import ru.cashflow.orchestrator.features.forecast.repository.AutoRefreshRepository;
import ru.cashflow.orchestrator.features.jobs.application.JobService;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoRefreshIntegrationTest extends AbstractIntegrationTest {

    @Autowired AutoRefreshService autoRefresh;
    @Autowired LedgerRepository ledger;
    @Autowired AutoRefreshRepository candidates;
    @Autowired JobService jobs;
    @Autowired CompanyAccess access;

    @ParameterizedTest
    @ValueSource(strings = {"Pacific/Kiritimati", "Pacific/Honolulu"})
    void automaticForecastUsesLastClosedDayInCompanyTimezone(String timezone) throws Exception {
        var ctx = context(timezone);
        LocalDate lastClosedDay = companyToday(ctx.company()).minusDays(1);

        assertThat(autoRefresh.reconcile()).isEqualTo(1);
        assertThat(jdbc.sql("select s.as_of from snapshots s join jobs j on j.snapshot_id=s.id "
                        + "where j.origin='AUTO'").query(LocalDate.class).single()).isEqualTo(lastClosedDay);
    }

    @Test
    void changedInputsDoNotPersistAJobUnderTheOldVersionKey() throws Exception {
        var ctx = context();
        long version = jdbc.sql("select data_version from companies where id=:id").param("id", ctx.company())
                .query(Long.class).single();
        var scope = access.systemScope(ctx.company());
        assertThatThrownBy(() -> jobs.createAutomaticForecast(scope, ctx.account(), companyToday(ctx.company()).minusDays(1),
                "WITH_KNOWN_PLANS", version - 1, "old-version-key"))
                .isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.code())
                        .isEqualTo("INPUT_CHANGED"));
        assertThat(jdbc.sql("select count(*) from jobs").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from snapshots").query(Long.class).single()).isZero();
    }

    @Test
    void insufficientHistoryAccountsDoNotStarveTheNextBatchAndAttemptsHaveACooldown() throws Exception {
        for (int index = 0; index < 5; index++) {
            UUID owner = UUID.randomUUID();
            UUID company = createCompany(owner, "Нет истории");
            createAccount(owner, company, null);
            jdbc.sql("update companies set data_version=100 where id=:id").param("id", company).update();
        }
        var valid = context();

        assertThat(autoRefresh.reconcile()).isZero();
        assertThat(jdbc.sql("select count(*) from auto_refresh_attempts").query(Long.class).single()).isEqualTo(5);
        assertThat(autoRefresh.reconcile()).isEqualTo(1);
        assertThat(send("GET", base(valid) + "/jobs", valid.owner(), null).body().path("items")).hasSize(1);
        assertThat(autoRefresh.reconcile()).isZero();
        assertThat(jdbc.sql("select count(*) from jobs").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void reservationExcludesConcurrentAttemptsAndExpiresWithoutChangingFinancialData() throws Exception {
        context();
        Instant now = Instant.now();
        var candidate = candidates.candidates(1, now).get(0);
        assertThat(candidates.reserve(candidate, now, now.plusSeconds(300))).isTrue();
        assertThat(candidates.reserve(candidate, now, now.plusSeconds(300))).isFalse();
        assertThat(candidates.candidates(1, now)).isEmpty();
        assertThat(candidates.candidates(1, now.plusSeconds(301))).hasSize(1);
        assertThat(candidates.reserve(candidate, now.plusSeconds(301), now.plusSeconds(600))).isTrue();

        jdbc.sql("update companies set data_version=data_version+1 where id=:id")
                .param("id", candidate.companyId()).update();
        var changed = candidates.candidates(1, now.plusSeconds(302)).get(0);
        assertThat(candidates.reserve(changed, now.plusSeconds(302), now.plusSeconds(600))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void historicalForecastWithNewerInputsDoesNotHideTheStaleCurrentForecast(boolean publishedHead) throws Exception {
        var ctx = context();
        long version = jdbc.sql("select data_version from companies where id=:id").param("id", ctx.company())
                .query(Long.class).single();
        UUID latest = savedForecast(ctx, companyToday(ctx.company()).minusDays(1), version - 1, "WITH_KNOWN_PLANS");
        savedForecast(ctx, companyToday(ctx.company()).minusDays(10), version, "HISTORY_ONLY");
        if (publishedHead) {
            jdbc.sql("insert into forecast_alert_heads(company_id,account_id,forecast_id) values(:c,:a,:f)")
                    .param("c", ctx.company()).param("a", ctx.account()).param("f", latest).update();
        }
        var dashboard = send("GET", base(ctx) + "/dashboard", ctx.owner(), null);
        assertThat(dashboard.status()).as(dashboard.raw()).isEqualTo(200);
        assertThat(dashboard.body().path("latestForecast").path("id").asText()).isEqualTo(latest.toString());
        assertThat(dashboard.body().path("refresh").path("needsRefresh").asBoolean()).isTrue();

        assertThat(autoRefresh.reconcile()).isEqualTo(1);
        assertThat(jdbc.sql("select s.payload->>'scope' from snapshots s join jobs j on j.snapshot_id=s.id "
                        + "where j.origin='AUTO'").query(String.class).single()).isEqualTo("WITH_KNOWN_PLANS");
    }

    @Test
    void reconcileCreatesOneAutoJobAndCancelsSupersededQueuedJob() throws Exception {
        var ctx = context();
        assertThat(autoRefresh.reconcile()).isEqualTo(1);

        var jobs = send("GET", base(ctx) + "/jobs", ctx.owner(), null).body().path("items");
        assertThat(jobs).hasSize(1);
        String first = jobs.get(0).path("id").asText();
        assertThat(jobs.get(0).path("origin").asText()).isEqualTo("AUTO");
        assertThat(jobs.get(0).path("status").asText()).isEqualTo("QUEUED");

        assertThat(autoRefresh.reconcile()).isZero();
        assertThat(send("GET", base(ctx) + "/jobs", ctx.owner(), null).body().path("items")).hasSize(1);

        createPlan(ctx, "10.00");
        assertThat(autoRefresh.reconcile()).isEqualTo(1);
        var afterChange = send("GET", base(ctx) + "/jobs", ctx.owner(), null).body().path("items");
        assertThat(afterChange).hasSize(2);
        assertThat(afterChange.findValuesAsText("origin")).contains("AUTO", "AUTO");
        assertThat(jdbc.sql("select status from jobs where id=:id").param("id", UUID.fromString(first))
                .query(String.class).single()).isEqualTo("CANCELLED");
    }

    @Test
    void runningJobBlocksANewAutomaticJobUntilItFinishes() throws Exception {
        var ctx = context();
        assertThat(autoRefresh.reconcile()).isEqualTo(1);
        String first = send("GET", base(ctx) + "/jobs", ctx.owner(), null).body().path("items").get(0)
                .path("id").asText();
        jdbc.sql("update jobs set status='RUNNING', lease_owner='test', lease_until=now() + interval '5 minutes' "
                        + "where id=:id")
                .param("id", UUID.fromString(first)).update();
        createPlan(ctx, "20.00");

        assertThat(autoRefresh.reconcile()).isZero();
        assertThat(jdbc.sql("select count(*) from jobs").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void dashboardExposesRefreshStateAndJobOrigin() throws Exception {
        var ctx = context();
        var dashboard = send("GET", base(ctx) + "/dashboard", ctx.owner(), null);
        assertThat(dashboard.status()).isEqualTo(200);
        assertThat(dashboard.body().path("refresh").path("needsRefresh").asBoolean()).isTrue();
        assertThat(dashboard.body().path("refresh").path("autoRefreshEnabled").asBoolean()).isFalse();
        assertThat(dashboard.body().path("refresh").path("latestForecastInputVersion").asLong()).isEqualTo(-1);
    }

    private Ctx context() throws Exception {
        return context(null);
    }

    private Ctx context(String timezone) throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Автообновление");
        if (timezone != null) {
            jdbc.sql("update companies set timezone=:timezone where id=:company")
                    .param("timezone", timezone).param("company", company).update();
        }
        UUID account = createAccount(owner, company, null);
        LocalDate today = companyToday(company);
        Instant now = Instant.now();
        ledger.insertPayment(new Payment(UUID.randomUUID(), company, account, null, null, null,
                today.minusDays(20), new BigDecimal("500.00"), Direction.IN, null, null, null, null, "OTHER",
                CategorySource.OTHER, null, null, 0, now, now));
        assertThat(send("POST", base(company, account) + "/balance-anchors", owner,
                "{\"date\":\"" + today.minusDays(40) + "\",\"balance\":\"100000.00\",\"reason\":\"тест\"}")
                .status()).isEqualTo(201);
        assertThat(send("POST", base(company, account) + "/completeness", owner,
                "{\"fromDate\":\"" + today.minusDays(40) + "\",\"toDate\":\"" + today.minusDays(1)
                        + "\",\"completeness\":\"CONFIRMED_COMPLETE\",\"reason\":\"тест\"}").status()).isEqualTo(201);
        return new Ctx(owner, company, account);
    }

    private UUID savedForecast(Ctx ctx, LocalDate asOf, long version, String scope) {
        UUID snapshot = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID result = UUID.randomUUID();
        jdbc.sql("insert into snapshots(id,company_id,account_id,kind,as_of,input_version,fingerprint,payload,"
                        + "provenance_json,created_at) values(:s,:c,:a,'FORECAST',:date,:v,:fp,'{}','{}',now())")
                .param("s", snapshot).param("c", ctx.company()).param("a", ctx.account()).param("date", asOf)
                .param("v", version).param("fp", "a".repeat(64)).update();
        jdbc.sql("insert into jobs(id,company_id,account_id,type,status,snapshot_id,result_id,progress,attempts,"
                        + "max_attempts,not_before,created_by,created_at) "
                        + "values(:j,:c,:a,'FORECAST','SUCCEEDED',:s,:r,100,1,3,now(),:u,now())")
                .param("j", job).param("c", ctx.company()).param("a", ctx.account()).param("s", snapshot)
                .param("r", result).param("u", ctx.owner()).update();
        String response = "{\"id\":\"" + result + "\",\"inputDataVersion\":" + version
                + ",\"scope\":\"" + scope + "\",\"algorithmVersion\":\"WEEKDAY_MEAN_V1\","
                + "\"settings\":{\"windowWeeks\":12},\"days\":[],\"gaps\":[]}";
        jdbc.sql("insert into forecasts(id,company_id,account_id,job_id,snapshot_id,as_of,horizon,algorithm_version,"
                        + "input_version,scope,quality,start_balance,start_balance_date,expected_minimum_balance,"
                        + "max_expected_shortfall,settings_json,risk_json,summary_json,response_json,created_at) "
                        + "values(:r,:c,:a,:j,:s,:date,30,'WEEKDAY_MEAN_V1',:v,:scope,'GOOD',100,:date,100,0,"
                        + "'{\"windowWeeks\":12}','{}','{}',cast(:body as jsonb),now())")
                .param("r", result).param("c", ctx.company()).param("a", ctx.account()).param("j", job)
                .param("s", snapshot).param("date", asOf).param("v", version).param("scope", scope)
                .param("body", response).update();
        return result;
    }

    private void createPlan(Ctx ctx, String amount) throws Exception {
        assertThat(send("POST", base(ctx) + "/expected-payments", ctx.owner(),
                "{\"title\":\"Новый план\",\"direction\":\"OUT\",\"amount\":\"" + amount
                        + "\",\"dueDate\":\"" + companyToday(ctx.company()).plusDays(3)
                        + "\",\"categoryCode\":\"SUPPLIERS\",\"relation\":\"ADDITIONAL\",\"source\":\"MANUAL\"}")
                .status()).isEqualTo(201);
    }

    private static String base(Ctx ctx) {
        return base(ctx.company(), ctx.account());
    }

    private static String base(UUID company, UUID account) {
        return "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;
    }

    private record Ctx(UUID owner, UUID company, UUID account) {
    }
}
