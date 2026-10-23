package ru.cashflow.authprofile.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record ProfileRs(
        UUID id,
        String email,
        @JsonInclude(JsonInclude.Include.ALWAYS) String displayName,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
