package ru.cashflow.forecast.api.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ru.cashflow.forecast.features.shared.model.Algorithm;
import ru.cashflow.forecast.features.shared.model.DataQuality;
import ru.cashflow.forecast.features.shared.model.ForecastScope;

public record ForecastRs(UUID id, UUID companyId, UUID accountId, UUID jobId, LocalDate asOfDate,
        int horizonDays, ForecastScope scope, Algorithm algorithm, String algorithmVersion,
        ForecastSettingsRs settings, long inputDataVersion, UUID snapshotId, String staleness,
        BigDecimal startBalance, LocalDate startBalanceDate, DataQuality quality, List<WarningRs> warnings,
        RiskEstimateRs riskEstimate, BigDecimal expectedMinimumBalance, LocalDate firstExpectedGapDate,
        BigDecimal maxExpectedShortfall, List<ForecastDayRs> days, List<GapEpisodeRs> gaps, Instant createdAt) { }
