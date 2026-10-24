package ru.cashflow.authprofile.features.profile.application.service;

import ru.cashflow.authprofile.features.profile.command.model.UpdateProfileData;
import ru.cashflow.authprofile.features.profile.model.UserProfile;

public interface ProfileManagementApplicationService {

    UserProfile update(UpdateProfileData data);
}
