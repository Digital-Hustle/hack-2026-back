package ru.cashflow.authprofile.features.profile.command.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record CreateProfileData(UUID userId, Instant createdAt) {}
