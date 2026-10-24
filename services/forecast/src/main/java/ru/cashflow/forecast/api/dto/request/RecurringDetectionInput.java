package ru.cashflow.forecast.api.dto.request;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RecurringDetectionInput(@NotNull UUID accountId, @NotNull LocalDate asOfDate,
        @NotNull @Min(0) Long inputDataVersion,
        @NotNull @Size(max = 250000) List<@NotNull @Valid SnapshotPayment> payments) { }
