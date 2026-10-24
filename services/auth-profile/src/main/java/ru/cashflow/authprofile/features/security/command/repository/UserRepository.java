package ru.cashflow.authprofile.features.security.command.repository;

import static ru.cashflow.authprofile.jooq.Tables.USERS;
import static ru.cashflow.authprofile.util.time.DatabaseTimestamp.utc;

import lombok.RequiredArgsConstructor;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ru.cashflow.authprofile.features.security.model.UserIdentity;

import java.time.Instant;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class UserRepository {

    private final DSLContext dsl;

    public UserIdentity createIfMissing(String email, Instant now) {
        var identity =
                UserIdentity.builder().id(UUID.randomUUID()).email(email).createdAt(now).build();

        dsl.insertInto(USERS)
                .set(dsl.newRecord(USERS, identity))
                .set(USERS.UPDATED_AT, utc(now))
                .onConflict(USERS.EMAIL)
                .doNothing()
                .execute();

        return dsl.select(USERS.ID, USERS.EMAIL, USERS.CREATED_AT)
                .from(USERS)
                .where(USERS.EMAIL.eq(email))
                .fetchSingleInto(UserIdentity.class);
    }
}
