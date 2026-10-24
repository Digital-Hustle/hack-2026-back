package ru.cashflow.authprofile.features.profile.command.service;

import ru.cashflow.authprofile.features.profile.command.model.CreateProfileData;
import ru.cashflow.authprofile.features.profile.command.model.UpdateProfileData;

public interface ProfileCommandService {

    void createIfMissing(CreateProfileData data);

    void update(UpdateProfileData data);
}
