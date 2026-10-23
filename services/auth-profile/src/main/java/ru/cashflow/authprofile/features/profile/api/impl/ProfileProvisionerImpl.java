package ru.cashflow.authprofile.features.profile.api.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import ru.cashflow.authprofile.features.profile.api.ProfileProvisioner;
import ru.cashflow.authprofile.features.profile.command.model.CreateProfileData;
import ru.cashflow.authprofile.features.profile.command.service.ProfileCommandService;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ProfileProvisionerImpl implements ProfileProvisioner {

    private final ProfileCommandService profiles;

    @Override
    public void createIfMissing(UUID userId, Instant registeredAt) {
        profiles.createIfMissing(
                CreateProfileData.builder().userId(userId).createdAt(registeredAt).build());
    }
}
