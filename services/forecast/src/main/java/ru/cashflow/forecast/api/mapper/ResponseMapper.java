package ru.cashflow.forecast.api.mapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import ru.cashflow.forecast.api.dto.response.BacktestRs;
import ru.cashflow.forecast.api.dto.response.BacktestOriginRs;
import ru.cashflow.forecast.api.dto.response.ForecastDayRs;
import ru.cashflow.forecast.api.dto.response.ForecastRs;
import ru.cashflow.forecast.api.dto.response.ForecastSettingsRs;
import ru.cashflow.forecast.api.dto.response.GapEpisodeRs;
import ru.cashflow.forecast.api.dto.response.GapMetricsRs;
import ru.cashflow.forecast.api.dto.response.HorizonMetricRs;
import ru.cashflow.forecast.api.dto.response.RecurringDetectionResult;
import ru.cashflow.forecast.api.dto.response.RiskEstimateRs;
import ru.cashflow.forecast.api.dto.response.RiskMetricsRs;
import ru.cashflow.forecast.api.dto.response.WarningRs;
import ru.cashflow.forecast.features.backtest.model.BacktestInput;
import ru.cashflow.forecast.features.backtest.model.BacktestResult;
import ru.cashflow.forecast.features.backtest.model.GapMetrics;
import ru.cashflow.forecast.features.backtest.model.HorizonMetric;
import ru.cashflow.forecast.features.backtest.model.OriginResult;
import ru.cashflow.forecast.features.backtest.model.RiskMetrics;
import ru.cashflow.forecast.features.forecast.model.CalculationResult;
import ru.cashflow.forecast.features.forecast.model.GapEpisode;
import ru.cashflow.forecast.features.recurring.model.DetectionResult;
import ru.cashflow.forecast.features.risk.model.RiskEstimate;
import ru.cashflow.forecast.features.shared.model.Settings;
import ru.cashflow.forecast.features.shared.model.Warning;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ResponseMapper {
    ForecastSettingsRs toSettings(Settings settings);
    RiskEstimateRs toRisk(RiskEstimate risk);
    List<WarningRs> toWarnings(List<Warning> warnings);
    List<GapEpisodeRs> toGaps(List<GapEpisode> gaps);
    List<HorizonMetricRs> toHorizons(List<HorizonMetric> horizons);
    GapMetricsRs toGapMetrics(GapMetrics metrics);
    RiskMetricsRs toRiskMetrics(RiskMetrics metrics);
    List<BacktestOriginRs> toOrigins(List<OriginResult> origins);
    RecurringDetectionResult toDetection(DetectionResult result);

    default ForecastRs toForecast(CalculationResult result, UUID id, UUID jobId) {
        var metadata = result.metadata();
        var point = result.point();
        var days = IntStream.range(0, point.days().size()).mapToObj(index -> {
            var day = point.days().get(index);
            var risk = result.risk().days().get(index);
            return new ForecastDayRs(day.date(), day.plannedIn(), day.plannedOut(), day.recurringIn(), day.recurringOut(),
                    day.residualIn(), day.residualOut(), day.inflow(), day.outflow(), day.balance(), risk.balanceP10(),
                    risk.balanceP50(), risk.balanceP90(), risk.pBelowZero(), risk.pBelowBuffer());
        }).toList();
        return new ForecastRs(id, metadata.companyId(), metadata.accountId(), jobId, metadata.asOfDate(), 30,
                metadata.scope(), metadata.settings().algorithm(), metadata.algorithmVersion(), toSettings(metadata.settings()),
                metadata.inputDataVersion(), metadata.snapshotId(), "CURRENT", metadata.startBalance(), metadata.startBalanceDate(),
                point.quality(), toWarnings(point.warnings()), toRisk(result.risk().estimate()), point.expectedMinimumBalance(),
                point.firstExpectedGapDate(), point.maxExpectedShortfall(), days, toGaps(result.gaps()), result.createdAt());
    }

    default BacktestRs toBacktest(BacktestResult result, BacktestInput input, UUID id, UUID jobId, Instant createdAt) {
        var request = input.request();
        return new BacktestRs(id, input.companyId(), input.accountId(), jobId, result.status(), input.settings().algorithm(),
                result.algorithmVersion(), request.scope(), request.windowWeeks(), request.firstOrigin(), request.lastOrigin(),
                request.stepDays(), input.snapshotId(), input.inputDataVersion(), "CURRENT", result.evaluatedOrigins(),
                result.skippedOrigins(), result.evaluatedDays(), result.maeBalance(), result.rmseBalance(), toHorizons(result.horizons()),
                toGapMetrics(result.gapMetrics()), toRiskMetrics(result.riskMetrics()), toOrigins(result.origins()), toWarnings(result.warnings()), createdAt);
    }
}
