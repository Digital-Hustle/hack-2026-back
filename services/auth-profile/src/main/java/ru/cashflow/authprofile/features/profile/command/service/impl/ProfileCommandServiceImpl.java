package ru.cashflow.authprofile.features.profile.command.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.authprofile.features.profile.command.model.CreateProfileData;
import ru.cashflow.authprofile.features.profile.command.model.UpdateProfileData;
import ru.cashflow.authprofile.features.profile.command.repository.ProfileRepository;
import ru.cashflow.authprofile.features.profile.command.service.ProfileCommandService;
import ru.cashflow.authprofile.features.profile.exceptions.ProfileVersionConflictException;
import ru.cashflow.authprofile.util.time.ApplicationClock;

@Service
@RequiredArgsConstructor
public class ProfileCommandServiceImpl implements ProfileCommandService {

    private final ProfileRepository profiles;
    private final ApplicationClock clock;

    @Override
    @Transactional
    public void createIfMissing(CreateProfileData data) {
        profiles.createIfMissing(data);
    }

    @Override
    @Transactional
    public void update(UpdateProfileData data) {
        if (!profiles.update(data.userId(), data.displayName(), data.version(), clock.now())) {
            throw new ProfileVersionConflictException();
        }
    }
}
