package ru.cashflow.authprofile.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;

/** Uses a real PostgreSQL. An external database also permits testing in root-only containers. */
final class PostgresTestSupport {
    private static EmbeddedPostgres postgres;

    static synchronized String url() {
        String external = System.getenv("TEST_DATABASE_URL");
        if (external != null) return external;
        try {
            if (postgres == null)
                postgres =
                        EmbeddedPostgres.builder()
                                .setServerConfig("unix_socket_directories", "")
                                .setServerConfig("listen_addresses", "127.0.0.1")
                                .start();
            return postgres.getJdbcUrl("postgres", "postgres");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start test PostgreSQL", exception);
        }
    }
}
