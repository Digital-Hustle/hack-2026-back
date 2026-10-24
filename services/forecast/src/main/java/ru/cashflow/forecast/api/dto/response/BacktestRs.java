package ru.cashflow.forecast.api.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ru.cashflow.forecast.features.shared.model.Algorithm;
import ru.cashflow.forecast.features.shared.model.ForecastScope;

public record BacktestRs(UUID id, UUID companyId, UUID accountId, UUID jobId, String status,
        Algorithm algorithm, String algorithmVersion, ForecastScope scope, int windowWeeks,
        LocalDate firstOrigin, LocalDate lastOrigin, int stepDays, UUID snapshotId, long inputDataVersion,
        String staleness, int evaluatedOrigins, int skippedOrigins, int evaluatedDays, BigDecimal maeBalance,
        BigDecimal rmseBalance, List<HorizonMetricRs> horizons, GapMetricsRs gapMetrics, RiskMetricsRs riskMetrics,
        List<BacktestOriginRs> origins, List<WarningRs> warnings, Instant createdAt) { }
