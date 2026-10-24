package ru.cashflow.forecast.units;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.features.backtest.application.BacktestScorer;
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
