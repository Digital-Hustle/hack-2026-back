package ru.cashflow.orchestrator.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import java.time.Instant;
import java.util.UUID;

class ResultQueryIntegrationTest extends AbstractIntegrationTest {
    @Test
    void dashboardHasHonestEmptyStateAndReadAccessIsTenantScoped() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Queries");
        UUID account = createAccount(owner, company, null);
        UUID viewer = UUID.randomUUID();
        addMember(owner, company, viewer, "VIEWER");
        String base = "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;
        var dashboard = send("GET", base + "/dashboard", viewer, null);
        assertThat(dashboard.status()).as(dashboard.raw()).isEqualTo(200);
        assertThat(dashboard.body().path("latestForecast").isNull()).isTrue();
        assertThat(dashboard.body().path("latestBacktest").isNull()).isTrue();
        assertThat(dashboard.body().path("currentBalance").isNull()).isTrue();
        assertThat(dashboard.body().path("quality").asText()).isEqualTo("INSUFFICIENT");
        assertThat(dashboard.body().path("runningJobs").isEmpty()).isTrue();
        assertThat(send("GET", base + "/forecasts/latest", viewer, null).status()).isEqualTo(404);
        assertThat(dashboard.body().path("permissions").path("canWrite").asBoolean()).isFalse();
        assertThat(send("GET", base + "/forecasts", viewer, null).body().path("items").isEmpty())
                .isTrue();
        assertThat(send("GET", base + "/backtests", viewer, null).body().path("items").isEmpty())
                .isTrue();
        assertThat(send("GET", base + "/dashboard", null, null).status()).isEqualTo(401);
        assertThat(send("GET", base + "/dashboard", UUID.randomUUID(), null).status())
                .isEqualTo(404);
        UUID foreignCompany = createCompany(owner, "Other");
        UUID foreignAccount = createAccount(owner, foreignCompany, null);
        assertThat(
                        send(
                                        "GET",
                                        "/api/v1/orchestrator/companies/"
                                                + company
                                                + "/accounts/"
                                                + foreignAccount
                                                + "/forecasts",
                                        owner,
                                        null)
                                .status())
                .isEqualTo(404);
        assertThat(send("GET", base + "/forecasts?limit=501", owner, null).status()).isEqualTo(422);
    }

    @Test
    void savedResultsAreReadWithoutRecalculationAndStalenessIsComputedFromCompanyVersion()
            throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Saved");
        UUID account = createAccount(owner, company, null);
        UUID snapshot = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID result = UUID.randomUUID();
        jdbc.sql(
                        "insert into snapshots(id, company_id, account_id, kind, as_of,"
                            + " input_version, fingerprint, payload, provenance_json, created_at) "
                            + "values(:id,:c,:a,'FORECAST','2026-10-23',0,:f,'{}','{}',:now)")
                .param("id", snapshot)
                .param("c", company)
                .param("a", account)
                .param("f", "a".repeat(64))
                .param("now", java.sql.Timestamp.from(Instant.now()))
                .update();
        jdbc.sql(
                        "insert into"
                            + " jobs(id,company_id,account_id,type,status,snapshot_id,result_id,progress,attempts,max_attempts,not_before,created_by,created_at)"
                            + " values(:id,:c,:a,'FORECAST','SUCCEEDED',:s,:r,100,1,3,now(),:u,now())")
                .param("id", job)
                .param("c", company)
                .param("a", account)
                .param("s", snapshot)
                .param("r", result)
                .param("u", owner)
                .update();
        String response =
                "{\"id\":\""
                        + result
                        + "\",\"inputDataVersion\":0,\"days\":[],\"gaps\":[],\"createdAt\":\"2000-01-01T00:00:00Z\"}";
        jdbc.sql(
                        "insert into"
                            + " forecasts(id,company_id,account_id,job_id,snapshot_id,as_of,horizon,algorithm_version,input_version,scope,quality,start_balance,start_balance_date,expected_minimum_balance,max_expected_shortfall,settings_json,risk_json,summary_json,response_json,created_at)"
                            + " values(:r,:c,:a,:j,:s,'2026-10-23',30,'WEEKDAY_MEAN_V1',0,'HISTORY_ONLY','GOOD',100,'2026-10-23',100,0,'{\"windowWeeks\":12}','{}','{}',cast(:body"
                            + " as jsonb),now())")
                .param("r", result)
                .param("c", company)
                .param("a", account)
                .param("j", job)
                .param("s", snapshot)
                .param("body", response)
                .update();
        String path =
                "/api/v1/orchestrator/companies/"
                        + company
                        + "/accounts/"
                        + account
                        + "/forecasts/"
                        + result;
        var current = send("GET", path, owner, null);
        assertThat(current.status()).as(current.raw()).isEqualTo(200);
        assertThat(current.body().path("staleness").asText()).isEqualTo("CURRENT");
        assertThat(current.body().path("createdAt").asText()).doesNotStartWith("2000");
        assertThat(
                        send(
                                        "GET",
                                        path.substring(0, path.lastIndexOf('/')) + "/latest",
                                        owner,
                                        null)
                                .body()
                                .path("id")
                                .asText())
                .isEqualTo(result.toString());
        jdbc.sql("update companies set data_version = data_version + 1 where id=:id")
                .param("id", company)
                .update();
        assertThat(send("GET", path, owner, null).body().path("staleness").asText())
                .isEqualTo("STALE");
        assertThat(
                        jdbc.sql("select response_json::text from forecasts where id=:id")
                                .param("id", result)
                                .query(String.class)
                                .single())
                .doesNotContain("STALE");
        UUID second = createAccount(owner, company, null);
        assertThat(
                        send(
                                        "GET",
                                        path.replace(account.toString(), second.toString()),
                                        owner,
                                        null)
                                .status())
                .isEqualTo(404);
    }
}
