package ru.cashflow.forecast.api.dto.request;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BacktestSnapshot(@NotNull UUID snapshotId, @NotNull UUID companyId, @NotNull UUID accountId,
        @NotNull @Min(0) Long inputDataVersion, @NotNull @Valid BacktestRq request,
        @NotNull @Valid ForecastSettings settings,
        @NotNull @Size(max = 100) List<@NotNull @Valid ForecastSnapshot> originSnapshots,
        @NotNull @Size(max = 3660) List<@NotNull @Valid DailyActualRs> actualDays) { }
