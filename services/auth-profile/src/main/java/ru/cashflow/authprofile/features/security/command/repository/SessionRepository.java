package ru.cashflow.authprofile.features.security.command.repository;

import static ru.cashflow.authprofile.jooq.Tables.REFRESH_CREDENTIALS;
import static ru.cashflow.authprofile.jooq.Tables.SESSIONS;
import static ru.cashflow.authprofile.util.time.DatabaseTimestamp.utc;

import lombok.RequiredArgsConstructor;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ru.cashflow.authprofile.features.security.model.RefreshCredential;
import ru.cashflow.authprofile.features.security.model.UserSession;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SessionRepository {

    private final DSLContext dsl;

    public Optional<UserSession> findForUpdate(UUID sessionId) {
        return dsl.select(
                        SESSIONS.ID,
                        SESSIONS.USER_ID,
                        SESSIONS.CREATED_AT,
                        SESSIONS.LAST_SEEN_AT,
                        SESSIONS.EXPIRES_AT,
                        SESSIONS.REVOKED_AT)
                .from(SESSIONS)
                .where(SESSIONS.ID.eq(sessionId))
                .forUpdate()
                .fetchOptionalInto(UserSession.class);
    }

    public Optional<RefreshCredential> findCredential(String hash) {
        return dsl.select(REFRESH_CREDENTIALS.SESSION_ID, REFRESH_CREDENTIALS.USED_AT)
                .from(REFRESH_CREDENTIALS)
                .where(REFRESH_CREDENTIALS.TOKEN_HASH.eq(hash))
                .fetchOptionalInto(RefreshCredential.class);
    }

    public void create(UUID id, UUID userId, Instant now, Instant expiresAt) {
        var session =
                UserSession.builder()
                        .id(id)
                        .userId(userId)
                        .createdAt(now)
                        .lastSeenAt(now)
                        .expiresAt(expiresAt)
                        .build();

        dsl.insertInto(SESSIONS).set(dsl.newRecord(SESSIONS, session)).execute();
    }

    public void addCredential(UUID sessionId, String hash, Instant now) {
        dsl.insertInto(REFRESH_CREDENTIALS)
                .set(REFRESH_CREDENTIALS.TOKEN_HASH, hash)
                .set(REFRESH_CREDENTIALS.SESSION_ID, sessionId)
                .set(REFRESH_CREDENTIALS.CREATED_AT, utc(now))
                .execute();
    }

    public void useCredential(String hash, Instant now) {
        dsl.update(REFRESH_CREDENTIALS)
                .set(REFRESH_CREDENTIALS.USED_AT, utc(now))
                .where(REFRESH_CREDENTIALS.TOKEN_HASH.eq(hash))
                .execute();
    }

    public void extend(UUID sessionId, Instant now, Instant expiresAt) {
        dsl.update(SESSIONS)
                .set(SESSIONS.LAST_SEEN_AT, utc(now))
                .set(SESSIONS.EXPIRES_AT, utc(expiresAt))
                .where(SESSIONS.ID.eq(sessionId))
                .execute();
    }

    public boolean recordActivity(UUID sessionId, UUID userId, Instant now) {
        return dsl.update(SESSIONS)
                        .set(SESSIONS.LAST_SEEN_AT, utc(now))
                        .where(SESSIONS.ID.eq(sessionId))
                        .and(SESSIONS.USER_ID.eq(userId))
                        .and(SESSIONS.REVOKED_AT.isNull())
                        .and(SESSIONS.EXPIRES_AT.gt(utc(now)))
                        .execute()
                == 1;
    }

    public boolean revoke(UUID sessionId, UUID userId, Instant now) {
        return dsl.update(SESSIONS)
                        .set(SESSIONS.REVOKED_AT, utc(now))
                        .where(SESSIONS.ID.eq(sessionId))
                        .and(SESSIONS.USER_ID.eq(userId))
                        .execute()
                == 1;
    }

    public void revokeAll(UUID userId, Instant now) {
        dsl.select(SESSIONS.ID)
                .from(SESSIONS)
                .where(SESSIONS.USER_ID.eq(userId))
                .orderBy(SESSIONS.ID)
                .forUpdate()
                .fetch();
        dsl.update(SESSIONS)
                .set(SESSIONS.REVOKED_AT, utc(now))
                .where(SESSIONS.USER_ID.eq(userId))
                .and(SESSIONS.REVOKED_AT.isNull())
                .execute();
    }
}
