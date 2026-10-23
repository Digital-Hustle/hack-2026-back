package ru.cashflow.authprofile.features.profile.command.model;

import lombok.Builder;

import java.util.UUID;

@Builder
public record UpdateProfileData(UUID userId, String displayName, long version) {}
