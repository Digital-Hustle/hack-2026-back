package ru.cashflow.forecast.api.dto.request;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CalibrationOriginSnapshot(@NotNull @Valid ForecastSnapshot input,
        @NotNull @Size(min = 30, max = 30) List<@NotNull @Valid DailyActualRs> actualDays,
        @NotNull @Size(max = 250000) List<@NotNull @Valid SnapshotPayment> actualPayments) { }
