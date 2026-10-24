package ru.cashflow.authprofile.features.profile.query.repository;

import static ru.cashflow.authprofile.jooq.Tables.PROFILES;
import static ru.cashflow.authprofile.jooq.Tables.USERS;

import lombok.RequiredArgsConstructor;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ru.cashflow.authprofile.features.profile.model.UserProfile;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ProfileQueryRepository {

    private final DSLContext dsl;

    /** Email is a read-only projection of the identity owned by security. */
    public Optional<UserProfile> findByUserId(UUID userId) {
        return dsl.select(
                        PROFILES.USER_ID.as("id"),
                        USERS.EMAIL,
                        PROFILES.DISPLAY_NAME,
                        PROFILES.VERSION,
                        PROFILES.CREATED_AT,
                        PROFILES.UPDATED_AT)
                .from(PROFILES)
                .join(USERS)
                .on(USERS.ID.eq(PROFILES.USER_ID))
                .where(PROFILES.USER_ID.eq(userId))
                .fetchOptionalInto(UserProfile.class);
    }
}
