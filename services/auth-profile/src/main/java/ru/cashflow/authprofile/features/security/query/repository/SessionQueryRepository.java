package ru.cashflow.authprofile.features.security.query.repository;

import static ru.cashflow.authprofile.jooq.Tables.SESSIONS;
import static ru.cashflow.authprofile.util.time.DatabaseTimestamp.utc;

import lombok.RequiredArgsConstructor;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ru.cashflow.authprofile.features.security.query.model.SessionInfo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SessionQueryRepository {

    private final DSLContext dsl;

    public List<SessionInfo> findActiveByUserId(UUID userId, UUID currentSessionId, Instant now) {
        return dsl.select(
                        SESSIONS.ID,
                        SESSIONS.CREATED_AT,
                        SESSIONS.LAST_SEEN_AT,
                        SESSIONS.EXPIRES_AT,
                        SESSIONS.ID.eq(currentSessionId).as("current"))
                .from(SESSIONS)
                .where(SESSIONS.USER_ID.eq(userId))
                .and(SESSIONS.REVOKED_AT.isNull())
                .and(SESSIONS.EXPIRES_AT.gt(utc(now)))
                .orderBy(SESSIONS.CREATED_AT.desc(), SESSIONS.ID.asc())
                .fetchInto(SessionInfo.class);
    }
}
