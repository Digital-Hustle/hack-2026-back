package ru.cashflow.forecast.units;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.features.backtest.application.BacktestScorer;
import ru.cashflow.forecast.features.backtest.model.Benchmark;
import ru.cashflow.forecast.features.forecast.model.Flows;
import ru.cashflow.forecast.features.forecast.model.GapEpisode;
import ru.cashflow.forecast.features.forecast.model.PointDay;
import ru.cashflow.forecast.features.forecast.model.PointForecast;
import ru.cashflow.forecast.features.risk.model.RiskAvailability;
import ru.cashflow.forecast.features.risk.model.RiskDay;
import ru.cashflow.forecast.features.risk.model.RiskEstimate;
import ru.cashflow.forecast.features.risk.model.RiskResult;
import ru.cashflow.forecast.features.shared.model.DataQuality;
import ru.cashflow.forecast.features.shared.model.DayFact;
import ru.cashflow.forecast.features.shared.model.DailyStatus;
import ru.cashflow.forecast.features.shared.model.Money;
import static org.assertj.core.api.Assertions.assertThat;

class BacktestScorerTest {
    private final LocalDate cutoff = LocalDate.of(2026, 9, 1);

    @Test
    void scoresGapMatchingLeadTimeDateAndShortfallWithUndefinedDenominators() {
        var scorer = new BacktestScorer();
        var actual = actuals("10.00", "-20.00", "-30.00", "10.00");
        var forecast = forecast("-10.00", "-40.00", "10.00", "10.00");
        scorer.addOrigin(cutoff, forecast, unavailable(), List.of(gap(1, 2, "40.00")), actual);
        var metrics = scorer.finish().gapMetrics();
        assertThat(metrics.truePositive()).isEqualTo(1);
        assertThat(metrics.falsePositive()).isZero();
        assertThat(metrics.falseNegative()).isZero();
        assertThat(metrics.precision()).isEqualTo(1.0);
        assertThat(metrics.recall()).isEqualTo(1.0);
        assertThat(metrics.medianLeadDays()).isEqualTo(2.0);
        assertThat(metrics.dateMaeDays()).isEqualTo(1.0);
        assertThat(metrics.shortfallMae()).isEqualTo("10.00");
        assertThat(new BacktestScorer().finish().gapMetrics().precision()).isNull();
    }

    @Test
    void maximizesOneToOneIntersectionAcrossCompetingEpisodes() {
        var scorer = new BacktestScorer();
        var actual = actuals("-10.00", "-10.00", "-10.00", "-10.00", "10.00", "-10.00", "-10.00", "-10.00");
        scorer.addOrigin(cutoff, forecast("10.00", "10.00", "10.00", "10.00", "10.00", "10.00", "10.00", "10.00"),
                unavailable(), List.of(gap(2, 8, "20.00"), gap(1, 1, "30.00")), actual);
        var metrics = scorer.finish().gapMetrics();
        assertThat(metrics.truePositive()).isEqualTo(2);
        assertThat(metrics.falsePositive()).isZero();
        assertThat(metrics.falseNegative()).isZero();
    }

    @Test
    void dailyBrierBinsAndCoverageUseKnownEodIncludingZero() {
        var scorer = new BacktestScorer();
        var actual = actuals("-10.00", "10.00", "0.00");
        var riskDays = new ArrayList<>(unavailable().days());
        riskDays.set(0, new RiskDay(new BigDecimal("-20.00"), Money.ZERO, new BigDecimal("20.00"), 0.2, 0.2));
        riskDays.set(1, new RiskDay(Money.ZERO, Money.ZERO, new BigDecimal("5.00"), 0.8, 0.8));
        riskDays.set(2, new RiskDay(Money.ZERO, Money.ZERO, Money.ZERO, 1.0, 1.0));
        var risk = new RiskResult(new RiskEstimate(RiskAvailability.AVAILABLE, "HISTORICAL_TRAJECTORY_BOOTSTRAP", 1.0, 100, 12, false, List.of()), riskDays, List.of());
        scorer.addOrigin(cutoff, forecast("0.00", "0.00", "0.00"), risk, List.of(), actual);
        var metrics = scorer.finish().riskMetrics();
        assertThat(metrics.evaluatedCount()).isEqualTo(3);
        assertThat(metrics.brierScore()).isCloseTo(0.76, org.assertj.core.data.Offset.offset(0.00000001));
        assertThat(metrics.interval80Coverage()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(0.00000001));
        assertThat(metrics.calibrationBins().get(0).meanProbability()).isNull();
        assertThat(metrics.calibrationBins().get(1).count()).isEqualTo(1);
        assertThat(metrics.calibrationBins().get(4).count()).isEqualTo(2);
        assertThat(metrics.calibrationBins().get(4).meanProbability()).isEqualTo(0.9);
    }

    @Test
    void dailyConfusionMatrixMseAndSkillAgainstNaiveUseTheSameScoredDays() {
        var scorer = new BacktestScorer("MODEL", "test model", Money.ZERO);
        var actual = actuals("10.00", "-20.00", "-30.00", "10.00");
        var naive = new Benchmark(BacktestScorer.NAIVE, "flat", false,
                List.of(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO),
                List.of(Flows.ZERO, Flows.ZERO, Flows.ZERO, Flows.ZERO));
        var origin = scorer.addOrigin(cutoff, forecast("-10.00", "-40.00", "10.00", "10.00"), unavailable(), List.of(),
                actual, List.of(naive));
        var quality = scorer.finish().quality();
        var matrix = quality.primary().dailyMatrix();
        assertThat(matrix.truePositive()).isEqualTo(1);
        assertThat(matrix.falsePositive()).isEqualTo(1);
        assertThat(matrix.falseNegative()).isEqualTo(1);
        assertThat(matrix.trueNegative()).isEqualTo(1);
        assertThat(matrix.accuracy()).isEqualTo(0.5);
        assertThat(matrix.f1()).isEqualTo(0.5);
        assertThat(matrix.balancedAccuracy()).isEqualTo(0.5);
        assertThat(quality.primary().maeBalance()).isEqualTo("20.00");
        assertThat(quality.primary().mseBalance()).isEqualTo("600.00");
        assertThat(quality.primary().rmseBalance()).isEqualTo("24.49");
        assertThat(quality.benchmarks().get(0).maeBalance()).isEqualTo("17.50");
        assertThat(quality.primary().skillScoreVsNaive()).isCloseTo(1 - 20.0 / 17.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(quality.benchmarks().get(0).skillScoreVsNaive()).isEqualTo(0.0);
        assertThat(quality.originMatrix().truePositive()).isEqualTo(1);
        assertThat(quality.originMatrix().trueNegative()).isZero();
        assertThat(quality.scenarioDailyMatrix()).isNull();
        assertThat(quality.bufferDailyMatrix()).isNull();
        assertThat(origin.days()).hasSize(4);
        assertThat(origin.days().get(1).predictedBreach()).isTrue();
        assertThat(origin.days().get(2).actualBreach()).isTrue();
        assertThat(origin.days().get(2).predictedBreach()).isFalse();
    }

    @Test
    void selectionChoosesOnEarlyOriginsAndReportsLaterHoldout() {
        var scorer = new BacktestScorer("MODEL", "test model", Money.ZERO);
        for (int week = 0; week < 3; week++) {
            var origin = cutoff.plusDays(7L * week);
            var actual = new ArrayList<DayFact>();
            var days = new ArrayList<PointDay>();
            for (int day = 1; day <= 2; day++) {
                actual.add(new DayFact(origin.plusDays(day), Money.ZERO, Money.ZERO, new BigDecimal("100.00"),
                        DailyStatus.ZERO_CONFIRMED, 0, false));
                days.add(new PointDay(origin.plusDays(day), Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO,
                        Money.ZERO, Money.ZERO, Money.ZERO, new BigDecimal(week < 2 ? "90.00" : "130.00")));
            }
            var challenger = new Benchmark("CHALLENGER", "candidate", true,
                    List.of(new BigDecimal("95.00"), new BigDecimal("95.00")), List.of(Flows.ZERO, Flows.ZERO));
            scorer.addOrigin(origin, new PointForecast(days, DataQuality.GOOD, List.of(), Set.of(), Money.ZERO, null,
                    Money.ZERO), unavailable(), List.of(), actual, List.of(challenger));
        }
        var selection = scorer.finish().quality().selection();
        assertThat(selection.selectedModel()).isEqualTo("CHALLENGER");
        assertThat(selection.selectionOrigins()).isEqualTo(2);
        assertThat(selection.holdoutOrigins()).isEqualTo(1);
        assertThat(selection.selectedHoldoutMae()).isEqualTo("5.00");
        assertThat(selection.primaryHoldoutMae()).isEqualTo("30.00");
    }

    private List<DayFact> actuals(String... balances) {
        var result = new ArrayList<DayFact>();
        for (int index = 0; index < balances.length; index++) {
            result.add(new DayFact(cutoff.plusDays(index + 1), Money.ZERO, Money.ZERO, new BigDecimal(balances[index]), DailyStatus.ZERO_CONFIRMED, 0, false));
        }
        return result;
    }

    private PointForecast forecast(String... balances) {
        var days = new ArrayList<PointDay>();
        for (int index = 0; index < balances.length; index++) {
            days.add(new PointDay(cutoff.plusDays(index + 1), Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO,
                    Money.ZERO, Money.ZERO, Money.ZERO, new BigDecimal(balances[index])));
        }
        return new PointForecast(days, DataQuality.GOOD, List.of(), Set.of(), Money.ZERO, null, Money.ZERO);
    }

    private GapEpisode gap(int from, int to, String shortfall) {
        return new GapEpisode(java.util.UUID.randomUUID(), "CASH_GAP", cutoff.plusDays(from), cutoff.plusDays(to), Money.ZERO,
                new BigDecimal(shortfall).negate(), new BigDecimal(shortfall), null, null, null, "CONDITIONAL_ON_BREACH", RiskAvailability.INSUFFICIENT_HISTORY);
    }

    private RiskResult unavailable() {
        return new RiskResult(new RiskEstimate(RiskAvailability.INSUFFICIENT_HISTORY, "HISTORICAL_TRAJECTORY_BOOTSTRAP", null, 0, 0, false, List.of()),
                java.util.Collections.nCopies(30, new RiskDay(null, null, null, null, null)), List.of());
    }
}
