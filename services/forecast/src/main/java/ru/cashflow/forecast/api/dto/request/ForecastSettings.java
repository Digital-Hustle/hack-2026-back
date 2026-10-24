package ru.cashflow.forecast.api.dto.request;

import java.math.BigDecimal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import ru.cashflow.forecast.features.shared.model.Algorithm;
import ru.cashflow.forecast.api.validation.AllowedInts;
import ru.cashflow.forecast.api.validation.MoneyValue;

public record ForecastSettings(
        @NotNull @AllowedInts({8, 10, 12}) Integer windowWeeks,
        @NotNull @AllowedInts({30}) Integer horizonDays,
        @NotNull @MoneyValue(sign = MoneyValue.Sign.NON_NEGATIVE) BigDecimal bufferAmount,
        @NotNull @Min(100) @Max(5000) Integer scenarioCount,
        @NotNull @Min(12) @Max(100) Integer minCalibrationOrigins,
        @NotNull @AllowedInts({7}) Integer calibrationStepDays,
        @NotNull Algorithm algorithm) { }
