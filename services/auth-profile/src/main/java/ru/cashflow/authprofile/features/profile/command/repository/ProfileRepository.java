package ru.cashflow.authprofile.features.profile.command.repository;

import static ru.cashflow.authprofile.features.profile.constants.ProfileConstants.MAX_PROFILE_VERSION;
import static ru.cashflow.authprofile.jooq.Tables.PROFILES;
import static ru.cashflow.authprofile.util.time.DatabaseTimestamp.utc;

import lombok.RequiredArgsConstructor;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ru.cashflow.authprofile.features.profile.command.model.CreateProfileData;

import java.time.Instant;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ProfileRepository {

    private final DSLContext dsl;

    public void createIfMissing(CreateProfileData data) {
        dsl.insertInto(PROFILES)
                .set(dsl.newRecord(PROFILES, data))
                .set(PROFILES.UPDATED_AT, utc(data.createdAt()))
                .onConflict(PROFILES.USER_ID)
                .doNothing()
                .execute();
    }

    public boolean update(UUID userId, String displayName, long expectedVersion, Instant now) {
        return dsl.update(PROFILES)
                        .set(PROFILES.DISPLAY_NAME, displayName)
                        .set(PROFILES.VERSION, PROFILES.VERSION.plus(1L))
                        .set(PROFILES.UPDATED_AT, utc(now))
                        .where(PROFILES.USER_ID.eq(userId))
                        .and(PROFILES.VERSION.eq(expectedVersion))
                        .and(PROFILES.VERSION.lt(MAX_PROFILE_VERSION))
                        .execute()
                == 1;
    }
}
