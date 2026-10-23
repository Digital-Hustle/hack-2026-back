package ru.cashflow.authprofile.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static ru.cashflow.authprofile.jooq.Tables.CODE_CHALLENGES;
import static ru.cashflow.authprofile.jooq.Tables.REFRESH_CREDENTIALS;
import static ru.cashflow.authprofile.jooq.Tables.SESSIONS;
import static ru.cashflow.authprofile.jooq.Tables.USERS;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ru.cashflow.authprofile.api.ApiPaths;
import ru.cashflow.authprofile.config.AbstractIntegrationTest;
import ru.cashflow.authprofile.exception.DependencyUnavailableException;
import ru.cashflow.authprofile.features.profile.api.ProfileLookup;
import ru.cashflow.authprofile.features.profile.api.ProfileProvisioner;

import java.util.UUID;

class AuthenticationRollbackIntegrationTest extends AbstractIntegrationTest {
    @MockitoSpyBean protected ProfileProvisioner unavailableProfiles;
    @MockitoSpyBean protected ProfileLookup profileLookup;

    @Test
    void profileFailureRollsBackCodeConsumptionUserAndSession() throws Exception {
        // given
        var challenge = challenge("rollback@example.com");
        doThrow(new DependencyUnavailableException())
                .when(unavailableProfiles)
                .createIfMissing(any(), any());

        // when
        verify(challenge, delivered.get("rollback@example.com"))
                .andExpect(status().isServiceUnavailable());

        // then
        assertThat(dsl.fetchCount(USERS)).isZero();
        assertThat(dsl.fetchCount(SESSIONS)).isZero();
        assertThat(
                        dsl.select(CODE_CHALLENGES.CONSUMED_AT)
                                .from(CODE_CHALLENGES)
                                .fetchSingle()
                                .value1())
                .isNull();
    }

    @Test
    void profileLookupFailureRollsBackRefreshRotation() throws Exception {
        // given
        var login = login("refresh-rollback@example.com");
        var sessionId = UUID.fromString(body(login).path("sessionId").asText());
        var sessionBefore = dsl.selectFrom(SESSIONS).where(SESSIONS.ID.eq(sessionId)).fetchSingle();
        doThrow(new DependencyUnavailableException()).when(profileLookup).getByUserId(any());

        // when
        mvc.perform(
                        mutation(post(BASE + "/auth/refresh"))
                                .cookie(login.getResponse().getCookie(ApiPaths.REFRESH_COOKIE)))
                .andExpect(status().isServiceUnavailable());

        // then
        var credential =
                dsl.selectFrom(REFRESH_CREDENTIALS)
                        .where(REFRESH_CREDENTIALS.SESSION_ID.eq(sessionId))
                        .fetchSingle();
        assertThat(credential.getUsedAt()).isNull();
        var sessionAfter = dsl.selectFrom(SESSIONS).where(SESSIONS.ID.eq(sessionId)).fetchSingle();
        assertThat(sessionAfter.getExpiresAt()).isEqualTo(sessionBefore.getExpiresAt());
        assertThat(sessionAfter.getRevokedAt()).isNull();
    }
}
