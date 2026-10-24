package ru.cashflow.authprofile.features.profile.api.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import ru.cashflow.authprofile.features.profile.api.ProfileLookup;
import ru.cashflow.authprofile.features.profile.api.ProfileSnapshot;
import ru.cashflow.authprofile.features.profile.query.service.ProfileQueryService;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ProfileLookupImpl implements ProfileLookup {

    private final ProfileQueryService profiles;

    @Override
    public ProfileSnapshot getByUserId(UUID userId) {
        var p = profiles.getByUserId(userId);

        return ProfileSnapshot.builder()
                .id(p.id())
                .email(p.email())
                .displayName(p.displayName())
                .version(p.version())
                .createdAt(p.createdAt())
                .updatedAt(p.updatedAt())
                .build();
    }
}
