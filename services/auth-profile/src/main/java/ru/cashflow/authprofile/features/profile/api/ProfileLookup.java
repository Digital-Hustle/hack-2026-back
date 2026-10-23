package ru.cashflow.authprofile.features.profile.api;

import java.util.UUID;

public interface ProfileLookup {

    ProfileSnapshot getByUserId(UUID userId);
}
