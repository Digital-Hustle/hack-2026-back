package ru.cashflow.codegen;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;

import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Logging;
import org.jooq.meta.jaxb.Target;

import java.nio.file.Path;

/** Build tooling only. No production database or local credentials are required. */
public final class GenerateJooq {
    private GenerateJooq() {}

    public static void main(String[] args) throws Exception {
        try (var postgres =
                        EmbeddedPostgres.builder()
                                .setServerConfig("unix_socket_directories", "")
                                .setServerConfig("listen_addresses", "127.0.0.1")
                                .start();
                var connection = postgres.getPostgresDatabase().getConnection();
                var resources = new DirectoryResourceAccessor(Path.of(args[0]));
                var liquibase =
                        new Liquibase(
                                "db/changelog/db.changelog-master.yaml",
                                resources,
                                DatabaseFactory.getInstance()
                                        .findCorrectDatabaseImplementation(
                                                new JdbcConnection(connection)))) {
            liquibase.update(new Contexts(), new LabelExpression());
            GenerationTool.generate(
                    new Configuration()
                            .withLogging(Logging.WARN)
                            .withJdbc(
                                    new Jdbc()
                                            .withDriver("org.postgresql.Driver")
                                            .withUrl(postgres.getJdbcUrl("postgres", "postgres"))
                                            .withUser("postgres")
                                            .withPassword("postgres"))
                            .withGenerator(
                                    new Generator()
                                            .withName("org.jooq.codegen.JavaGenerator")
                                            .withDatabase(
                                                    new Database()
                                                            .withName(
                                                                    "org.jooq.meta.postgres.PostgresDatabase")
                                                            .withInputSchema("auth_profile")
                                                            .withExcludes(
                                                                    "databasechangelog|databasechangeloglock"))
                                            .withGenerate(
                                                    new Generate()
                                                            .withRecords(true)
                                                            .withDeprecated(false)
                                                            .withJavaTimeTypes(true))
                                            .withTarget(
                                                    new Target()
                                                            .withPackageName(
                                                                    "ru.cashflow.authprofile.jooq")
                                                            .withDirectory(args[1]))));
        }
    }
}
