package ru.cashflow.authprofile.features.security.command.service;

import java.util.UUID;

public interface SessionActivityCommandService {

    void recordActivity(UUID userId, UUID sessionId);
}
