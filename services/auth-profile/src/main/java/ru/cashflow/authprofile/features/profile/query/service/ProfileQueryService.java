package ru.cashflow.authprofile.features.profile.query.service;

import ru.cashflow.authprofile.features.profile.model.UserProfile;

import java.util.UUID;

public interface ProfileQueryService {

    UserProfile getByUserId(UUID userId);
}
