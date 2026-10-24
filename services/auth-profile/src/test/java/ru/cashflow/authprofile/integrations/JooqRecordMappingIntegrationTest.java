package ru.cashflow.authprofile.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import static ru.cashflow.authprofile.jooq.Tables.PROFILES;
import static ru.cashflow.authprofile.jooq.Tables.USERS;

import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import ru.cashflow.authprofile.config.AbstractIntegrationTest;
import ru.cashflow.authprofile.features.profile.model.UserProfile;
import ru.cashflow.authprofile.features.security.query.model.SessionInfo;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

class JooqRecordMappingIntegrationTest extends AbstractIntegrationTest {

    @Test
    void mapsJoinedProfileByRecordComponentNamesInsteadOfColumnOrder() throws Exception {
        // given
        var login = login("mapping@example.test");
        UUID userId = UUID.fromString(body(login).path("user").path("id").asText());

        // when: select order differs from the record's canonical constructor.
        var profile =
                dsl.select(
                                PROFILES.VERSION,
                                PROFILES.UPDATED_AT,
                                USERS.EMAIL,
                                PROFILES.USER_ID.as("id"),
                                PROFILES.DISPLAY_NAME,
                                PROFILES.CREATED_AT)
                        .from(PROFILES)
                        .join(USERS)
                        .on(USERS.ID.eq(PROFILES.USER_ID))
                        .where(PROFILES.USER_ID.eq(userId))
                        .fetchSingleInto(UserProfile.class);

        // then
        assertThat(profile.id()).isEqualTo(userId);
        assertThat(profile.email()).isEqualTo("mapping@example.test");
        assertThat(profile.displayName()).isNull();
        assertThat(profile.version()).isZero();
        assertThat(profile.createdAt())
                .isEqualTo(Instant.parse(body(login).path("user").path("createdAt").asText()));
        assertThat(profile.updatedAt()).isEqualTo(profile.createdAt());
    }

    @Test
    void convertsOffsetTimestampsAndBooleanProjectionIntoSessionRecord() {
        // given
        UUID sessionId = UUID.randomUUID();
        var timestamp = OffsetDateTime.parse("2026-10-24T11:30:00+03:00");

        // when
        var session =
                dsl.select(
                                DSL.val(true).as("current"),
                                DSL.val(timestamp.plusHours(1)).as("expires_at"),
                                DSL.val(sessionId).as("id"),
                                DSL.val(timestamp).as("created_at"),
                                DSL.val(timestamp.plusMinutes(1)).as("last_seen_at"))
                        .fetchSingleInto(SessionInfo.class);

        // then
        assertThat(session.id()).isEqualTo(sessionId);
        assertThat(session.current()).isTrue();
        assertThat(session.createdAt()).isEqualTo(timestamp.toInstant());
        assertThat(session.lastSeenAt()).isEqualTo(timestamp.plusMinutes(1).toInstant());
        assertThat(session.expiresAt()).isEqualTo(timestamp.plusHours(1).toInstant());
    }
}
