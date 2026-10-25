package ru.cashflow.forecast.api.mapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import ru.cashflow.forecast.api.dto.response.BacktestRs;
import ru.cashflow.forecast.api.dto.response.BacktestOriginRs;
import ru.cashflow.forecast.api.dto.response.ConfusionMatrixRs;
import ru.cashflow.forecast.api.dto.response.DayComparisonRs;
import ru.cashflow.forecast.api.dto.response.ModelScoreRs;
import ru.cashflow.forecast.api.dto.response.ModelSelectionRs;
import ru.cashflow.forecast.api.dto.response.QualityMetricsRs;
import ru.cashflow.forecast.api.dto.response.TraceStepRs;
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
import ru.cashflow.forecast.features.backtest.model.ConfusionMatrix;
import ru.cashflow.forecast.features.backtest.model.DayComparison;
import ru.cashflow.forecast.features.backtest.model.ModelScore;
import ru.cashflow.forecast.features.backtest.model.QualityMetrics;
import ru.cashflow.forecast.features.backtest.model.GapMetrics;
import ru.cashflow.forecast.features.backtest.model.HorizonMetric;
import ru.cashflow.forecast.features.backtest.model.OriginResult;
import ru.cashflow.forecast.features.backtest.model.RiskMetrics;
import ru.cashflow.forecast.features.forecast.model.CalculationResult;
import ru.cashflow.forecast.features.forecast.model.GapEpisode;
import ru.cashflow.forecast.features.recurring.model.DetectionResult;
import ru.cashflow.forecast.features.risk.model.RiskEstimate;
import ru.cashflow.forecast.features.shared.model.Settings;
import ru.cashflow.forecast.features.shared.model.TraceStep;
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
                point.firstExpectedGapDate(), point.maxExpectedShortfall(), days, toGaps(result.gaps()), result.createdAt(),
                toTrace(result.trace()));
    }

    default BacktestRs toBacktest(BacktestResult result, BacktestInput input, UUID id, UUID jobId, Instant createdAt) {
        var request = input.request();
        return new BacktestRs(id, input.companyId(), input.accountId(), jobId, result.status(), input.settings().algorithm(),
                result.algorithmVersion(), request.scope(), request.windowWeeks(), request.firstOrigin(), request.lastOrigin(),
                request.stepDays(), input.snapshotId(), input.inputDataVersion(), "CURRENT", result.evaluatedOrigins(),
                result.skippedOrigins(), result.evaluatedDays(), result.maeBalance(), result.rmseBalance(), toHorizons(result.horizons()),
                toGapMetrics(result.gapMetrics()), toRiskMetrics(result.riskMetrics()), toOrigins(result.origins()), toWarnings(result.warnings()), createdAt,
                toQuality(result.quality()), toTrace(result.trace()));
    }

    default List<BacktestOriginRs> toOrigins(List<OriginResult> origins) {
        return origins.stream().map(origin -> new BacktestOriginRs(origin.asOfDate(), origin.status(), origin.reason(),
                origin.maeBalance(), origin.evaluatedDays(), origin.days().stream().map(this::toDay).toList())).toList();
    }

    default DayComparisonRs toDay(DayComparison day) {
        return new DayComparisonRs(day.date(), day.horizonDay(), day.forecastBalance(), day.actualBalance(),
                day.forecastInflow(), day.forecastOutflow(), day.actualInflow(), day.actualOutflow(), day.balanceP10(),
                day.balanceP90(), day.pBelowZero(), day.predictedBreach(), day.actualBreach());
    }

    default List<TraceStepRs> toTrace(List<TraceStep> trace) {
        return trace.stream().map(step -> new TraceStepRs(step.seq(), step.stage(), step.message(), step.data())).toList();
    }

    default ConfusionMatrixRs toMatrix(ConfusionMatrix matrix) {
        return matrix == null ? null : new ConfusionMatrixRs(matrix.threshold(), matrix.truePositive(),
                matrix.falsePositive(), matrix.falseNegative(), matrix.trueNegative(), matrix.total(), matrix.accuracy(),
                matrix.precision(), matrix.recall(), matrix.specificity(), matrix.f1(), matrix.balancedAccuracy(),
                matrix.prevalence(), matrix.majorityBaselineAccuracy());
    }

    default ModelScoreRs toScore(ModelScore score) {
        return new ModelScoreRs(score.model(), score.description(), score.evaluatedDays(), score.maeBalance(),
                score.mseBalance(), score.rmseBalance(), score.wapeBalance(), score.skillScoreVsNaive(), score.inflowMae(),
                score.outflowMae(), toMatrix(score.dailyMatrix()));
    }

    default QualityMetricsRs toQuality(QualityMetrics quality) {
        if (quality == null) {
            return null;
        }
        var selection = quality.selection();
        return new QualityMetricsRs(toScore(quality.primary()), quality.benchmarks().stream().map(this::toScore).toList(),
                toMatrix(quality.originMatrix()), toMatrix(quality.scenarioDailyMatrix()),
                toMatrix(quality.bufferDailyMatrix()), quality.scenarioThreshold(),
                selection == null ? null : new ModelSelectionRs(selection.selectedModel(), selection.holdoutFrom(),
                        selection.selectionOrigins(), selection.holdoutOrigins(), selection.selectedHoldoutMae(),
                        selection.primaryHoldoutMae(), selection.candidates().stream().map(candidate ->
                                new ModelSelectionRs.CandidateRs(candidate.model(), candidate.selectionMae(),
                                        candidate.holdoutMae())).toList()),
                quality.notes());
    }
}
