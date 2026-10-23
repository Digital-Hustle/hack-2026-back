package ru.cashflow.authprofile.features.security.command.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.authprofile.features.security.command.repository.UserRepository;
import ru.cashflow.authprofile.features.security.command.service.UserCommandService;
import ru.cashflow.authprofile.features.security.model.UserIdentity;
import ru.cashflow.authprofile.util.time.ApplicationClock;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UserCommandServiceImpl implements UserCommandService {

    private final UserRepository users;
    private final ApplicationClock clock;

    @Override
    @Transactional
    public UserIdentity createIfMissing(String email) {
        return users.createIfMissing(email.toLowerCase(Locale.ROOT), clock.now());
    }
}
