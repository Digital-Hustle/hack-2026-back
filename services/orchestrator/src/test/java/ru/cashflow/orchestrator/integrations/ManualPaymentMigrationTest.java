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

class ManualPaymentMigrationTest {

    @Test
    void existingFactsBecomeImportedAndManualColumnsAreAdditive() throws Exception {
        String url = PostgresTestSupport.url();
        String database = "manual_payment_migration_" + UUID.randomUUID().toString().replace("-", "");
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
                UUID payment = UUID.randomUUID();
                jdbc.sql("insert into companies(id,name,timezone,owner_user_id,created_at,updated_at) "
                                + "values(:c,'Migration','Europe/Moscow',:owner,now(),now())")
                        .param("c", company).param("owner", UUID.randomUUID()).update();
                jdbc.sql("insert into accounts(id,company_id,name,created_at,updated_at) values(:a,:c,'Main',now(),now())")
                        .param("a", account).param("c", company).update();
                jdbc.sql("insert into payments(id,company_id,account_id,operation_date,amount,direction,category_code,"
                                + "category_source,version,known_at,created_at) values(:p,:c,:a,'2026-09-01',10.00,'OUT',"
                                + "'OTHER','RULE',0,now(),now())")
                        .param("p", payment).param("c", company).param("a", account).update();

                migrations.update(new Contexts(), new LabelExpression());

                assertThat(jdbc.sql("select source from payments where id=:id").param("id", payment)
                        .query(String.class).single()).isEqualTo("IMPORT");
                assertThat(jdbc.sql("select created_by is null from payments where id=:id").param("id", payment)
                        .query(Boolean.class).single()).isTrue();
                assertThat(jdbc.sql("select manual_note is null from payments where id=:id").param("id", payment)
                        .query(Boolean.class).single()).isTrue();
                assertThat(jdbc.sql("select count(*) from payments where source='MANUAL'").query(Long.class).single())
                        .isZero();
            } finally {
                try (var statement = admin.createStatement()) {
                    statement.execute("drop database " + database);
                }
            }
        }
    }
}
