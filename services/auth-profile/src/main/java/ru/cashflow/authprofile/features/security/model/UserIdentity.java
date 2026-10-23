package ru.cashflow.authprofile.features.security.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record UserIdentity(UUID id, String email, Instant createdAt) {}
