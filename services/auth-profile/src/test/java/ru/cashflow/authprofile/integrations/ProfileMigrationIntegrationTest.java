package ru.cashflow.authprofile.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import static ru.cashflow.authprofile.jooq.Tables.PROFILES;
import static ru.cashflow.authprofile.jooq.Tables.USERS;
import static ru.cashflow.authprofile.util.time.DatabaseTimestamp.utc;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import ru.cashflow.authprofile.config.AbstractIntegrationTest;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

class ProfileMigrationIntegrationTest extends AbstractIntegrationTest {
    @Test
    void migrationPreservesExistingNameVersionAndTimestamps() throws Exception {
        // given: a separate database with only the original migration applied
        String databaseName = "migration_" + UUID.randomUUID().toString().replace("-", "");
        String url;
        try (var connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL();
        }
        int query = url.indexOf('?');
        String options = query < 0 ? "" : url.substring(query);
        String prefix = query < 0 ? url : url.substring(0, query);
        String migrationUrl =
                prefix.substring(0, prefix.lastIndexOf('/') + 1) + databaseName + options;
        dsl.createDatabase(databaseName).execute();
        try (var connection = DriverManager.getConnection(migrationUrl, "postgres", "postgres");
                var resources = new ClassLoaderResourceAccessor();
                var migrations =
                        new Liquibase(
                                "db/changelog/db.changelog-master.yaml",
                                resources,
                                DatabaseFactory.getInstance()
                                        .findCorrectDatabaseImplementation(
                                                new JdbcConnection(connection)))) {
            migrations.update(1, new Contexts(), new LabelExpression());
            var database = DSL.using(connection, SQLDialect.POSTGRES);
            var id = UUID.randomUUID();
            var created = utc(Instant.parse("2026-10-01T09:00:00Z"));
            var updated = utc(Instant.parse("2026-10-20T09:00:00Z"));
            database.insertInto(USERS)
                    .set(USERS.ID, id)
                    .set(USERS.EMAIL, "legacy@example.com")
                    .set(USERS.DISPLAY_NAME, "Существующий профиль")
                    .set(USERS.VERSION, 7L)
                    .set(USERS.CREATED_AT, created)
                    .set(USERS.UPDATED_AT, updated)
                    .execute();

            // when
            migrations.update(new Contexts(), new LabelExpression());

            // then
            var profile =
                    database.selectFrom(PROFILES).where(PROFILES.USER_ID.eq(id)).fetchSingle();
            assertThat(profile.getDisplayName()).isEqualTo("Существующий профиль");
            assertThat(profile.getVersion()).isEqualTo(7L);
            assertThat(profile.getCreatedAt()).isEqualTo(created);
            assertThat(profile.getUpdatedAt()).isEqualTo(updated);
            assertThat(database.fetchCount(USERS)).isEqualTo(1);
        } finally {
            dsl.dropDatabase(databaseName).execute();
        }
    }
}
