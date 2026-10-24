package ru.cashflow.forecast.api.dto.request;

import java.time.LocalDate;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import ru.cashflow.forecast.features.shared.model.ForecastScope;
import ru.cashflow.forecast.api.validation.AllowedInts;

public record BacktestRq(@NotNull UUID accountId, @NotNull LocalDate firstOrigin,
        @NotNull LocalDate lastOrigin, @NotNull @Min(7) @Max(30) Integer stepDays,
        @NotNull ForecastScope scope, @NotNull @AllowedInts({8, 10, 12}) Integer windowWeeks,
        @NotNull Boolean evaluateRisk) { }
