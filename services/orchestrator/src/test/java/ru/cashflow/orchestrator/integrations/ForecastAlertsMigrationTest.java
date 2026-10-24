package ru.cashflow.orchestrator.integrations;

import java.sql.DriverManager;
import java.util.UUID;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import ru.cashflow.orchestrator.support.PostgresTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastAlertsMigrationTest {

    @Test
    void upgradeRepairsLegacyGapIdsAndCreatesAlertsFromSavedResultsWithoutRecalculation() throws Exception {
        String url = PostgresTestSupport.url();
        String database = "alerts_migration_" + UUID.randomUUID().toString().replace("-", "");
        int query = url.indexOf('?');
        String options = query < 0 ? "" : url.substring(query);
        String prefix = query < 0 ? url : url.substring(0, query);
        String migrationUrl = prefix.substring(0, prefix.lastIndexOf('/') + 1) + database + options;
        try (var admin = DriverManager.getConnection(url, "postgres", "postgres")) {
            try (var statement = admin.createStatement()) {
                statement.execute("create database " + database);
            }
            try (var connection = DriverManager.getConnection(migrationUrl, "postgres", "postgres");
                 var resources = new ClassLoaderResourceAccessor();
                 var migrations = new Liquibase("db/changelog/db.changelog-master.yaml", resources,
                         DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
                migrations.update(11, new Contexts(), new LabelExpression());
                JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                UUID company = UUID.randomUUID();
                UUID account = UUID.randomUUID();
                UUID gap = UUID.randomUUID();
                UUID plan = UUID.randomUUID();
                jdbc.sql("insert into companies(id,name,timezone,owner_user_id,created_at,updated_at) "
                                + "values(:c,'Migration','Europe/Moscow',:owner,now(),now())")
                        .param("c", company).param("owner", UUID.randomUUID()).update();
                jdbc.sql("insert into accounts(id,company_id,name,created_at,updated_at) values(:a,:c,'Main',now(),now())")
                        .param("a", account).param("c", company).update();
                UUID old = savedLegacyForecast(jdbc, company, account, gap, plan, "2026-10-24T10:00:00Z", "2026-10-24T12:00:00Z");
                UUID current = savedLegacyForecast(jdbc, company, account, gap, plan, "2026-10-24T11:00:00Z", "2026-10-24T11:00:30Z");
                String before = jdbc.sql("select response_json::text from forecasts where id=:id").param("id", old)
                        .query(String.class).single();

                migrations.update(new Contexts(), new LabelExpression());

                assertThat(jdbc.sql("select id from forecast_gaps order by forecast_id").query(UUID.class).list())
                        .containsExactly(gap, gap);
                assertThat(jdbc.sql("select forecast_id from forecast_alert_heads").query(UUID.class).single())
                        .isEqualTo(current);
                assertThat(jdbc.sql("select count(*) from alerts").query(Long.class).single()).isEqualTo(6);
                assertThat(jdbc.sql("select distinct forecast_id from alerts where active").query(UUID.class).list())
                        .containsExactly(current);
                assertThat(jdbc.sql("select gap_id from alerts where code='CASH_GAP'").query(UUID.class).list())
                        .containsExactly(gap, gap);
                assertThat(jdbc.sql("select plan_id from alerts where code='OVERDUE_PLAN'").query(UUID.class).list())
                        .containsExactly(plan, plan);
                assertThat(jdbc.sql("select count(*) from alerts where probability is not null").query(Long.class).single())
                        .isZero();
                assertThat(jdbc.sql("select response_json::text from forecasts where id=:id").param("id", old)
                        .query(String.class).single()).isEqualTo(before);
                migrations.update(new Contexts(), new LabelExpression());
                assertThat(jdbc.sql("select count(*) from alerts").query(Long.class).single()).isEqualTo(6);
            } finally {
                try (var statement = admin.createStatement()) {
                    statement.execute("drop database " + database);
                }
            }
        }
    }

    private static UUID savedLegacyForecast(JdbcClient jdbc, UUID company, UUID account, UUID gap, UUID plan,
                                             String queuedAt, String completedAt) {
        UUID snapshot = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID result = UUID.randomUUID();
        String input = "{\"knownPlans\":[{\"id\":\"" + plan + "\",\"direction\":\"IN\",\"dueDate\":\"2026-10-22\","
                + "\"status\":\"OPEN\",\"remainingAmount\":\"400.00\"}]}";
        String response = "{\"id\":\"" + result + "\",\"riskEstimate\":{\"availability\":\"INSUFFICIENT_HISTORY\"},"
                + "\"warnings\":[{\"code\":\"SHORT_HISTORY\",\"message\":\"Short history\",\"date\":null}],"
                + "\"gaps\":[{\"id\":\"" + gap + "\",\"kind\":\"CASH_GAP\",\"fromDate\":\"2026-10-29\","
                + "\"toDate\":\"2026-11-12\"}]}";
        jdbc.sql("insert into snapshots(id,company_id,account_id,kind,as_of,input_version,fingerprint,payload,provenance_json,created_at) "
                        + "values(:s,:c,:a,'FORECAST','2026-10-24',0,:fp,cast(:body as jsonb),'{}',cast(:queued as timestamptz))")
                .param("s", snapshot).param("c", company).param("a", account).param("fp", "a".repeat(64))
                .param("body", input).param("queued", queuedAt).update();
        jdbc.sql("insert into jobs(id,company_id,account_id,type,status,snapshot_id,result_id,progress,attempts,max_attempts,"
                        + "not_before,created_by,created_at) values(:j,:c,:a,'FORECAST','SUCCEEDED',:s,:r,100,1,3,"
                        + "cast(:queued as timestamptz),:u,cast(:queued as timestamptz))")
                .param("j", job).param("c", company).param("a", account).param("s", snapshot).param("r", result)
                .param("queued", queuedAt).param("u", UUID.randomUUID()).update();
        jdbc.sql("insert into forecasts(id,company_id,account_id,job_id,snapshot_id,as_of,horizon,algorithm_version,"
                        + "input_version,scope,quality,start_balance,start_balance_date,expected_minimum_balance,max_expected_shortfall,"
                        + "settings_json,risk_json,summary_json,response_json,created_at) values(:r,:c,:a,:j,:s,'2026-10-24',"
                        + "30,'WEEKDAY_MEAN_V1',0,'WITH_KNOWN_PLANS','LIMITED',1000,'2026-10-24',-500,500,'{}','{}','{}',"
                        + "cast(:body as jsonb),cast(:completed as timestamptz))")
                .param("r", result).param("c", company).param("a", account).param("j", job).param("s", snapshot)
                .param("body", response).param("completed", completedAt).update();
        jdbc.sql("insert into forecast_gaps(id,forecast_id,company_id,account_id,kind,from_date,to_date,threshold,"
                        + "point_min,point_shortfall,risk_availability) values(:id,:f,:c,:a,'CASH_GAP','2026-10-29',"
                        + "'2026-11-12',0,-500,500,'INSUFFICIENT_HISTORY')")
                .param("id", UUID.randomUUID()).param("f", result).param("c", company).param("a", account).update();
        return result;
    }
}
