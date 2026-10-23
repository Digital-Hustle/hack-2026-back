package ru.cashflow.authprofile.features.profile.query.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.authprofile.features.profile.exceptions.ProfileNotFoundException;
import ru.cashflow.authprofile.features.profile.model.UserProfile;
import ru.cashflow.authprofile.features.profile.query.repository.ProfileQueryRepository;
import ru.cashflow.authprofile.features.profile.query.service.ProfileQueryService;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProfileQueryServiceImpl implements ProfileQueryService {

    private final ProfileQueryRepository profiles;

    @Override
    public UserProfile getByUserId(UUID userId) {
        return profiles.findByUserId(userId).orElseThrow(ProfileNotFoundException::new);
    }
}
