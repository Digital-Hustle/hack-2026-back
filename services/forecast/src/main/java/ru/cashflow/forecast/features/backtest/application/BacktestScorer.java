package ru.cashflow.forecast.features.backtest.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ru.cashflow.forecast.features.backtest.model.CalibrationBin;
import ru.cashflow.forecast.features.backtest.model.GapMetrics;
import ru.cashflow.forecast.features.backtest.model.HorizonMetric;
import ru.cashflow.forecast.features.backtest.model.OriginResult;
import ru.cashflow.forecast.features.backtest.model.ScoringResult;
import ru.cashflow.forecast.features.backtest.model.RiskMetrics;
import ru.cashflow.forecast.features.forecast.model.GapEpisode;
import ru.cashflow.forecast.features.forecast.model.PointForecast;
import ru.cashflow.forecast.features.risk.model.RiskResult;
import ru.cashflow.forecast.features.shared.model.DayFact;
import ru.cashflow.forecast.features.shared.model.Money;

/** Request-local scorer; outcomes never enter the point model. */
public class BacktestScorer {
    private final Errors total = new Errors();
    private final Errors[] horizons = new Errors[30];
    private int truePositive;
    private int falsePositive;
    private int falseNegative;
    private final List<BigDecimal> leads = new ArrayList<>();
    private BigDecimal dateErrorSum = BigDecimal.ZERO;
    private BigDecimal shortfallErrorSum = Money.ZERO;
    private double brierSum;
    private int riskCount;
    private int intervalCount;
    private int coveredCount;
    private final int[] binCounts = new int[5];
    private final double[] binProbabilitySums = new double[5];
    private final int[] binObservedCounts = new int[5];

    public BacktestScorer() {
        Arrays.setAll(horizons, ignored -> new Errors());
    }

    public OriginResult addOrigin(LocalDate cutoff, PointForecast forecast, RiskResult risk,
            List<GapEpisode> gaps, List<DayFact> actualDays) {
        Map<LocalDate, DayFact> actualByDate = new HashMap<>();
        actualDays.forEach(day -> actualByDate.put(day.date(), day));
        Errors originErrors = new Errors();
        for (int horizon = 0; horizon < forecast.days().size(); horizon++) {
            var day = forecast.days().get(horizon);
            var actual = actualByDate.get(day.date());
            if (actual == null || !actual.complete() || actual.balanceEod() == null) {
                continue;
            }
            BigDecimal error = day.balance().subtract(actual.balanceEod());
            originErrors.add(error);
            total.add(error);
            horizons[horizon].add(error);
            var riskDay = risk.days().get(horizon);
            if (riskDay.pBelowZero() != null) {
                double probability = riskDay.pBelowZero();
                int observed = actual.balanceEod().signum() < 0 ? 1 : 0;
                double difference = probability - observed;
                brierSum += difference * difference;
                riskCount++;
                int bin = Math.min(4, (int) (probability * 5));
                binCounts[bin]++;
                binProbabilitySums[bin] += probability;
                binObservedCounts[bin] += observed;
            }
            if (riskDay.balanceP10() != null && riskDay.balanceP90() != null) {
                intervalCount++;
                if (actual.balanceEod().compareTo(riskDay.balanceP10()) >= 0 && actual.balanceEod().compareTo(riskDay.balanceP90()) <= 0) {
                    coveredCount++;
                }
            }
        }
        scoreGaps(cutoff, gaps, actualDays);
        return new OriginResult(cutoff, "EVALUATED", null, originErrors.mae(), originErrors.count);
    }

    public ScoringResult finish() {
        var horizonMetrics = new ArrayList<HorizonMetric>();
        for (int index = 0; index < 30; index++) {
            horizonMetrics.add(new HorizonMetric(index + 1, horizons[index].mae(), horizons[index].rmse(), horizons[index].count));
        }
        var bins = new ArrayList<CalibrationBin>();
        for (int index = 0; index < 5; index++) {
            bins.add(new CalibrationBin(index / 5.0, (index + 1) / 5.0, binCounts[index],
                    binCounts[index] == 0 ? null : binProbabilitySums[index] / binCounts[index], ratio(binObservedCounts[index], binCounts[index])));
        }
        var gapMetrics = new GapMetrics(truePositive, falsePositive, falseNegative, ratio(truePositive, truePositive + falsePositive),
                ratio(truePositive, truePositive + falseNegative), leads.isEmpty() ? null : Money.quantile(leads, new BigDecimal("0.5")).doubleValue(),
                truePositive == 0 ? null : dateErrorSum.divide(BigDecimal.valueOf(truePositive), MathContext.DECIMAL128).doubleValue(),
                truePositive == 0 ? null : Money.mean(shortfallErrorSum, truePositive));
        return new ScoringResult(total.count, total.mae(), total.rmse(), horizonMetrics, gapMetrics,
                new RiskMetrics(riskCount == 0 ? null : brierSum / riskCount, riskCount, bins, ratio(coveredCount, intervalCount)));
    }

    private void scoreGaps(LocalDate cutoff, List<GapEpisode> gaps, List<DayFact> actualDays) {
        var known = actualDays.stream().filter(day -> day.complete() && day.balanceEod() != null)
                .sorted(Comparator.comparing(DayFact::date)).toList();
        var actual = new ArrayList<Episode>();
        LocalDate from = null;
        LocalDate to = null;
        BigDecimal shortfall = Money.ZERO;
        for (var day : known) {
            if (from != null && (!day.date().equals(to.plusDays(1)) || day.balanceEod().signum() >= 0)) {
                actual.add(new Episode(from, to, shortfall));
                from = null;
                shortfall = Money.ZERO;
            }
            if (day.balanceEod().signum() < 0) {
                if (from == null) {
                    from = day.date();
                }
                to = day.date();
                shortfall = shortfall.max(day.balanceEod().negate());
            }
        }
        if (from != null) {
            actual.add(new Episode(from, to, shortfall));
        }
        var predicted = new ArrayList<Episode>();
        for (var gap : gaps) {
            if (!gap.kind().equals("CASH_GAP")) {
                continue;
            }
            var comparableDates = known.stream().map(DayFact::date).filter(date -> !date.isBefore(gap.fromDate()) && !date.isAfter(gap.toDate())).toList();
            if (!comparableDates.isEmpty()) {
                predicted.add(new Episode(comparableDates.getFirst(), comparableDates.getLast(), gap.expectedShortfall()));
            }
        }
        predicted.sort(Comparator.comparing(Episode::from));
        int[] matches = maximumIntersectionMatching(predicted, actual);
        int matched = 0;
        for (int index = 0; index < predicted.size(); index++) {
            int actualIndex = matches[index];
            if (actualIndex >= 0 && actualIndex < actual.size() && overlap(predicted.get(index), actual.get(actualIndex)) > 0) {
                matched++;
                var expected = predicted.get(index);
                var observed = actual.get(actualIndex);
                leads.add(BigDecimal.valueOf(ChronoUnit.DAYS.between(cutoff, observed.from())));
                dateErrorSum = dateErrorSum.add(BigDecimal.valueOf(Math.abs(ChronoUnit.DAYS.between(expected.from(), observed.from()))));
                shortfallErrorSum = shortfallErrorSum.add(expected.shortfall().subtract(observed.shortfall()).abs());
            }
        }
        truePositive += matched;
        falsePositive += predicted.size() - matched;
        falseNegative += actual.size() - matched;
    }

    /** Hungarian assignment: maximize total overlapping days, then matches; deterministic earlier-date ties. */
    private int[] maximumIntersectionMatching(List<Episode> predicted, List<Episode> actual) {
        int size = Math.max(predicted.size(), actual.size());
        long[][] cost = new long[size + 1][size + 1];
        for (int row = 1; row <= predicted.size(); row++) {
            for (int column = 1; column <= actual.size(); column++) {
                long days = overlap(predicted.get(row - 1), actual.get(column - 1));
                cost[row][column] = days == 0 ? 0 : -(days * 1_000_000L + 10_000L + (size - row) * 100L + size - column);
            }
        }
        long[] u = new long[size + 1];
        long[] v = new long[size + 1];
        int[] assignment = new int[size + 1];
        int[] way = new int[size + 1];
        for (int row = 1; row <= size; row++) {
            assignment[0] = row;
            long[] min = new long[size + 1];
            Arrays.fill(min, Long.MAX_VALUE);
            boolean[] used = new boolean[size + 1];
            int column = 0;
            do {
                used[column] = true;
                int currentRow = assignment[column];
                long delta = Long.MAX_VALUE;
                int next = 0;
                for (int candidate = 1; candidate <= size; candidate++) {
                    if (!used[candidate]) {
                        long reduced = cost[currentRow][candidate] - u[currentRow] - v[candidate];
                        if (reduced < min[candidate]) {
                            min[candidate] = reduced;
                            way[candidate] = column;
                        }
                        if (min[candidate] < delta) {
                            delta = min[candidate];
                            next = candidate;
                        }
                    }
                }
                for (int candidate = 0; candidate <= size; candidate++) {
                    if (used[candidate]) {
                        u[assignment[candidate]] += delta;
                        v[candidate] -= delta;
                    } else {
                        min[candidate] -= delta;
                    }
                }
                column = next;
            } while (assignment[column] != 0);
            do {
                int previous = way[column];
                assignment[column] = assignment[previous];
                column = previous;
            } while (column != 0);
        }
        int[] result = new int[predicted.size()];
        Arrays.fill(result, -1);
        for (int column = 1; column <= size; column++) {
            if (assignment[column] > 0 && assignment[column] <= predicted.size()) {
                result[assignment[column] - 1] = column - 1;
            }
        }
        return result;
    }

    private long overlap(Episode left, Episode right) {
        LocalDate from = left.from().isAfter(right.from()) ? left.from() : right.from();
        LocalDate to = left.to().isBefore(right.to()) ? left.to() : right.to();
        return Math.max(0, ChronoUnit.DAYS.between(from, to) + 1);
    }

    private Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private record Episode(LocalDate from, LocalDate to, BigDecimal shortfall) { }

    private static final class Errors {
        private int count;
        private BigDecimal absolute = Money.ZERO;
        private BigDecimal squared = BigDecimal.ZERO;

        private void add(BigDecimal error) {
            count++;
            absolute = absolute.add(error.abs());
            squared = squared.add(error.multiply(error));
        }

        private BigDecimal mae() {
            return count == 0 ? null : Money.mean(absolute, count);
        }

        private BigDecimal rmse() {
            return count == 0 ? null : Money.cents(squared.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128).sqrt(MathContext.DECIMAL128));
        }
    }
}
