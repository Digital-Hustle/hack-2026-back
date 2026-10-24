package ru.cashflow.authprofile.features.profile.api;

import java.time.Instant;
import java.util.UUID;

public interface ProfileProvisioner {

    void createIfMissing(UUID userId, Instant registeredAt);
}
