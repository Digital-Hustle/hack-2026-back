package ru.cashflow.authprofile.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import static ru.cashflow.authprofile.jooq.Tables.SESSIONS;

import org.junit.jupiter.api.Test;

import ru.cashflow.authprofile.config.AbstractIntegrationTest;

import java.time.Duration;
import java.util.UUID;

class SessionQueryIntegrationTest extends AbstractIntegrationTest {
    @Test
    void queryingSessionsDoesNotChangeLastSeenOrRefreshExpiry() throws Exception {
        // given
        var login = login("reader@example.com");
        var userId = UUID.fromString(body(login).path("user").path("id").asText());
        var sessionId = UUID.fromString(body(login).path("sessionId").asText());
        var before = dsl.selectFrom(SESSIONS).where(SESSIONS.ID.eq(sessionId)).fetchSingle();
        clock.advance(Duration.ofMinutes(2));

        // when
        var sessions = sessionQueries.getActiveSessions(userId, sessionId);

        // then
        assertThat(sessions).hasSize(1);
        var after = dsl.selectFrom(SESSIONS).where(SESSIONS.ID.eq(sessionId)).fetchSingle();
        assertThat(after.getLastSeenAt()).isEqualTo(before.getLastSeenAt());
        assertThat(after.getExpiresAt()).isEqualTo(before.getExpiresAt());
    }
}
