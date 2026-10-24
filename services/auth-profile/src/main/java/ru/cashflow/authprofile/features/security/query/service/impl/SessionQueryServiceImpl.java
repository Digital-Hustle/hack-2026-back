package ru.cashflow.authprofile.features.security.query.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.authprofile.features.security.query.model.SessionInfo;
import ru.cashflow.authprofile.features.security.query.repository.SessionQueryRepository;
import ru.cashflow.authprofile.features.security.query.service.SessionQueryService;
import ru.cashflow.authprofile.util.time.ApplicationClock;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SessionQueryServiceImpl implements SessionQueryService {

    private final SessionQueryRepository sessions;
    private final ApplicationClock clock;

    @Override
    public List<SessionInfo> getActiveSessions(UUID userId, UUID currentSessionId) {
        return sessions.findActiveByUserId(userId, currentSessionId, clock.nowAtSecond());
    }
}
