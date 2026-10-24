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

class PublicContractMigrationTest {

    @Test
    void upgradeFromImportOptionsPreservesExistingImportsAndDoesNotCountDuplicatesTwice() throws Exception {
        String url = PostgresTestSupport.url();
        String database = "public_contract_" + UUID.randomUUID().toString().replace("-", "");
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
                migrations.update(10, new Contexts(), new LabelExpression());
                JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                UUID company = UUID.randomUUID();
                UUID account = UUID.randomUUID();
                UUID owner = UUID.randomUUID();
                UUID committed = UUID.randomUUID();
                UUID staged = UUID.randomUUID();
                jdbc.sql("insert into companies(id,name,timezone,owner_user_id,created_at,updated_at) "
                                + "values(:c,'Migration','Europe/Moscow',:u,now(),now())")
                        .param("c", company).param("u", owner).update();
                jdbc.sql("insert into accounts(id,company_id,name,created_at,updated_at) "
                                + "values(:a,:c,'Main',now(),now())")
                        .param("a", account).param("c", company).update();
                insertImport(jdbc, company, account, owner, committed, true);
                insertImport(jdbc, company, account, owner, staged, false);

                migrations.update(new Contexts(), new LabelExpression());

                assertThat(jdbc.sql("select status from imports where id=:id").param("id", committed)
                        .query(String.class).single()).isEqualTo("COMMITTED");
                assertThat(jdbc.sql("select total_rows from imports where id=:id").param("id", committed)
                        .query(Integer.class).single()).isEqualTo(5);
                assertThat(jdbc.sql("select accepted_rows from imports where id=:id").param("id", committed)
                        .query(Integer.class).single()).isEqualTo(2);
                assertThat(jdbc.sql("select duplicate_rows from imports where id=:id").param("id", committed)
                        .query(Integer.class).single()).isEqualTo(3);
                assertThat(jdbc.sql("select warning_count from imports where id=:id").param("id", committed)
                        .query(Long.class).single()).isZero();
                assertThat(jdbc.sql("select from_date::text || '/' || to_date::text from imports where id=:id")
                        .param("id", committed).query(String.class).single()).isEqualTo("2026-09-01/2026-09-30");
                assertThat(jdbc.sql("select data_version from imports where id=:id").param("id", committed)
                        .query(Long.class).single()).isEqualTo(7);
                assertThat(jdbc.sql("select request_hash from imports where id=:id").param("id", committed)
                        .query(String.class).single()).isEqualTo("b".repeat(64));
                assertThat(jdbc.sql("select file_name from imports where id=:id").param("id", committed)
                        .query(String.class).single()).isEqualTo("original.csv");
                assertThat(jdbc.sql("select status from imports where id=:id").param("id", staged)
                        .query(String.class).single()).isEqualTo("STAGING");
                assertThat(jdbc.sql("select accepted_rows from imports where id=:id").param("id", staged)
                        .query(Integer.class).single()).isEqualTo(1);
                migrations.update(new Contexts(), new LabelExpression());
                assertThat(jdbc.sql("select duplicate_rows from imports where id=:id").param("id", committed)
                        .query(Integer.class).single()).isEqualTo(3);
            } finally {
                try (var statement = admin.createStatement()) {
                    statement.execute("drop database " + database);
                }
            }
        }
    }

    private static void insertImport(JdbcClient jdbc, UUID company, UUID account, UUID owner, UUID id,
                                      boolean committed) {
        jdbc.sql("insert into imports(id,company_id,account_id,status,idempotency_key,request_hash,input_fingerprint,"
                        + "schema_version,rules_version,preprocessor_status,rows_staged,rows_inserted,rows_already_present,"
                        + "issue_count,created_by,created_at,file_name,file_sha256,total_rows,rejected_rows,duplicate_rows,"
                        + "warning_count,completeness,declared_from_date,declared_to_date,data_version) "
                        + "values(:id,:c,:a,:status,:key,:hash,:hash,'1','R1','PREPARED',:rows,:inserted,:present,1,:u,now(),"
                        + "'original.csv',:hash,:total,0,:duplicates,0,:completeness,cast(:from as date),cast(:to as date),:v)")
                .param("id", id).param("c", company).param("a", account).param("u", owner)
                .param("status", committed ? "FINALIZED" : "STAGED").param("key", id.toString())
                .param("hash", "b".repeat(64)).param("rows", committed ? 3 : 1)
                .param("inserted", committed ? 2 : null).param("present", committed ? 1 : null)
                .param("total", committed ? 5 : 1).param("duplicates", committed ? 2 : 0)
                .param("completeness", committed ? "CONFIRMED_COMPLETE" : "UNKNOWN")
                .param("from", committed ? "2026-09-01" : null).param("to", committed ? "2026-09-30" : null)
                .param("v", committed ? 7L : null).update();
    }
}
