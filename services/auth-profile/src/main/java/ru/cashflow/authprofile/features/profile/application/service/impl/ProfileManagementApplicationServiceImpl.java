package ru.cashflow.authprofile.features.profile.application.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.authprofile.features.profile.application.service.ProfileManagementApplicationService;
import ru.cashflow.authprofile.features.profile.command.model.UpdateProfileData;
import ru.cashflow.authprofile.features.profile.command.service.ProfileCommandService;
import ru.cashflow.authprofile.features.profile.model.UserProfile;
import ru.cashflow.authprofile.features.profile.query.service.ProfileQueryService;

@Service
@RequiredArgsConstructor
public class ProfileManagementApplicationServiceImpl
        implements ProfileManagementApplicationService {
    private final ProfileCommandService commands;
    private final ProfileQueryService queries;

    @Override
    @Transactional
    public UserProfile update(UpdateProfileData data) {
        commands.update(data);

        return queries.getByUserId(data.userId());
    }
}
