package ru.cashflow.orchestrator.support;

import java.io.IOException;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/** Real PostgreSQL without Docker. TEST_DATABASE_URL may point at an external instance instead. */
public final class PostgresTestSupport {

    private static EmbeddedPostgres postgres;

    private PostgresTestSupport() {
    }

    public static synchronized String url() {
        String external = System.getenv("TEST_DATABASE_URL");
        if (external != null) {
            return external;
        }
        try {
            if (postgres == null) {
                postgres = EmbeddedPostgres.builder()
                        .setServerConfig("unix_socket_directories", "")
                        .setServerConfig("listen_addresses", "127.0.0.1")
                        .start();
            }
            return postgres.getJdbcUrl("postgres", "postgres");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start test PostgreSQL", exception);
        }
    }
}
