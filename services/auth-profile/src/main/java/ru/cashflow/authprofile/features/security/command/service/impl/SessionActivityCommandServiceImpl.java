package ru.cashflow.authprofile.features.security.command.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.authprofile.features.security.command.repository.SessionRepository;
import ru.cashflow.authprofile.features.security.command.service.SessionActivityCommandService;
import ru.cashflow.authprofile.features.security.exceptions.AuthenticationRequiredException;
import ru.cashflow.authprofile.util.time.ApplicationClock;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SessionActivityCommandServiceImpl implements SessionActivityCommandService {

    private final SessionRepository sessions;
    private final ApplicationClock clock;

    @Override
    @Transactional
    public void recordActivity(UUID userId, UUID sessionId) {
        if (!sessions.recordActivity(sessionId, userId, clock.nowAtSecond())) {
            throw new AuthenticationRequiredException();
        }
    }
}
