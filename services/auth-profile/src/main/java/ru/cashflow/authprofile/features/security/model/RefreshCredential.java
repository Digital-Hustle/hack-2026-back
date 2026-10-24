package ru.cashflow.authprofile.features.security.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record RefreshCredential(UUID sessionId, Instant usedAt) {}
