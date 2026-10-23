package ru.cashflow.authprofile.features.security.command.repository;

import static ru.cashflow.authprofile.jooq.Tables.CODE_CHALLENGES;
import static ru.cashflow.authprofile.util.time.DatabaseTimestamp.utc;

import lombok.RequiredArgsConstructor;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ru.cashflow.authprofile.features.security.model.CodeChallenge;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class CodeRepository {

    private final DSLContext dsl;

    public Optional<CodeChallenge> findForUpdate(UUID id) {
        return dsl.select(
                        CODE_CHALLENGES.ID,
                        CODE_CHALLENGES.EMAIL,
                        CODE_CHALLENGES.CODE_HASH,
                        CODE_CHALLENGES.ATTEMPTS,
                        CODE_CHALLENGES.EXPIRES_AT,
                        CODE_CHALLENGES.CONSUMED_AT)
                .from(CODE_CHALLENGES)
                .where(CODE_CHALLENGES.ID.eq(id))
                .forUpdate()
                .fetchOptionalInto(CodeChallenge.class);
    }

    public void create(UUID id, String email, String hash, Instant now, Instant expiresAt) {
        dsl.update(CODE_CHALLENGES)
                .set(CODE_CHALLENGES.CONSUMED_AT, utc(now))
                .where(CODE_CHALLENGES.EMAIL.eq(email))
                .and(CODE_CHALLENGES.CONSUMED_AT.isNull())
                .execute();
        var challenge =
                CodeChallenge.builder()
                        .id(id)
                        .email(email)
                        .codeHash(hash)
                        .expiresAt(expiresAt)
                        .build();

        dsl.insertInto(CODE_CHALLENGES)
                .set(dsl.newRecord(CODE_CHALLENGES, challenge))
                .set(CODE_CHALLENGES.CREATED_AT, utc(now))
                .execute();
    }

    public void incrementAttempts(UUID id) {
        dsl.update(CODE_CHALLENGES)
                .set(CODE_CHALLENGES.ATTEMPTS, CODE_CHALLENGES.ATTEMPTS.plus(1))
                .where(CODE_CHALLENGES.ID.eq(id))
                .execute();
    }

    public void consume(UUID id, Instant now) {
        dsl.update(CODE_CHALLENGES)
                .set(CODE_CHALLENGES.CONSUMED_AT, utc(now))
                .where(CODE_CHALLENGES.ID.eq(id))
                .execute();
    }
}
