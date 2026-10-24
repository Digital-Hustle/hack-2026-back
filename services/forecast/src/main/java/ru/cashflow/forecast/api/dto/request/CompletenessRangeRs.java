package ru.cashflow.forecast.api.dto.request;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import ru.cashflow.forecast.features.shared.model.Completeness;

public record CompletenessRangeRs(@NotNull UUID id, @NotNull UUID accountId,
        @NotNull LocalDate fromDate, @NotNull LocalDate toDate, @NotNull Completeness completeness,
        @NotBlank String reason, @NotNull Instant createdAt) { }
