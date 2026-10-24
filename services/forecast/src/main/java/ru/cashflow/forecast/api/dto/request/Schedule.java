package ru.cashflow.forecast.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import ru.cashflow.forecast.features.shared.model.Frequency;
import ru.cashflow.forecast.features.shared.model.BusinessDayRule;

public record Schedule(@NotNull Frequency frequency, @Min(1) @Max(7) Integer weekday,
        @Min(1) @Max(31) Integer dayOfMonth, @NotNull BusinessDayRule businessDayRule) { }
