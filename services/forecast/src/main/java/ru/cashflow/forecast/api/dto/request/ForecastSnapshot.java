package ru.cashflow.forecast.api.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ru.cashflow.forecast.features.shared.model.ForecastScope;
import ru.cashflow.forecast.api.validation.MoneyValue;

public record ForecastSnapshot(@NotNull UUID snapshotId, @NotNull UUID companyId, @NotNull UUID accountId,
        @NotNull LocalDate asOfDate, @NotNull @Min(0) Long inputDataVersion,
        @NotBlank @Size(max = 100) String algorithmVersion, @NotNull @Valid ForecastSettings settings,
        @NotNull ForecastScope scope, @NotNull @MoneyValue BigDecimal startBalance,
        @NotNull LocalDate startBalanceDate, @NotNull LocalDate historyFrom,
        @NotNull @Size(max = 3660) List<@NotNull @Valid DailyActualRs> dailyHistory,
        @NotNull @Size(max = 250000) List<@NotNull @Valid SnapshotPayment> payments,
        @NotNull @Size(max = 10000) List<@NotNull @Valid ExpectedPaymentRs> knownPlans,
        @NotNull @Size(max = 10000) List<@NotNull @Valid RecurringPatternRs> recurringPatterns,
        @NotNull @Size(max = 10000) List<@NotNull @Valid CompletenessRangeRs> completeRanges,
        @NotNull @Size(max = 100) List<@NotNull @Valid CalibrationOriginSnapshot> calibrationOrigins,
        @NotNull @Pattern(regexp = "[a-f0-9]{64}") String fingerprint) { }
