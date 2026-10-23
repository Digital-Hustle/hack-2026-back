package ru.cashflow.authprofile.features.security.query.service;

import ru.cashflow.authprofile.features.security.query.model.SessionInfo;

import java.util.List;
import java.util.UUID;

public interface SessionQueryService {

    List<SessionInfo> getActiveSessions(UUID userId, UUID currentSessionId);
}
