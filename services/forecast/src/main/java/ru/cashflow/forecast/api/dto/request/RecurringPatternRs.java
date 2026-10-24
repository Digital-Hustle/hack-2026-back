package ru.cashflow.forecast.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import ru.cashflow.forecast.api.validation.MoneyValue;
import ru.cashflow.forecast.features.shared.model.Direction;
import ru.cashflow.forecast.features.shared.model.PatternStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecurringPatternRs(
        @NotNull UUID id,
        @NotNull UUID accountId,
        @NotNull Direction direction,
        @NotBlank String categoryCode,
        String counterpartyKey,
        @NotNull PatternStatus status,
        @NotNull @Valid Schedule schedule,
        @NotNull @MoneyValue(sign = MoneyValue.Sign.POSITIVE) BigDecimal amount,
        @NotNull @Min(3) Integer observations,
        @NotNull LocalDate firstSeen,
        @NotNull LocalDate lastSeen,
        @NotNull @Min(0) Long inputDataVersion,
        @NotBlank String detectorVersion,
        @NotNull @Min(0) Long version) {}
