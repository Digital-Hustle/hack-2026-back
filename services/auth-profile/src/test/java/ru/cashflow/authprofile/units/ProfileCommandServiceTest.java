package ru.cashflow.authprofile.units;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import ru.cashflow.authprofile.features.profile.command.model.UpdateProfileData;
import ru.cashflow.authprofile.features.profile.command.repository.ProfileRepository;
import ru.cashflow.authprofile.features.profile.command.service.impl.ProfileCommandServiceImpl;
import ru.cashflow.authprofile.features.profile.exceptions.ProfileVersionConflictException;
import ru.cashflow.authprofile.util.time.ApplicationClock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class ProfileCommandServiceTest {
    @Test
    void staleVersionBecomesDomainConflict() {
        // given
        Instant now = Instant.parse("2026-10-23T10:00:00Z");
        var repository = mock(ProfileRepository.class);
        var service =
                new ProfileCommandServiceImpl(
                        repository, new ApplicationClock(Clock.fixed(now, ZoneOffset.UTC)));
        var data =
                UpdateProfileData.builder()
                        .userId(UUID.randomUUID())
                        .displayName("Updated name")
                        .version(3)
                        .build();
        when(repository.update(data.userId(), data.displayName(), data.version(), now))
                .thenReturn(false);
        // when / then
        assertThatThrownBy(() -> service.update(data))
                .isInstanceOf(ProfileVersionConflictException.class);
    }
}
