package ru.cashflow.forecast.features.backtest.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.cashflow.forecast.features.backtest.model.Benchmark;
import ru.cashflow.forecast.features.backtest.model.ConfusionMatrix;
import ru.cashflow.forecast.features.backtest.model.DayComparison;
import ru.cashflow.forecast.features.backtest.model.ModelScore;
import ru.cashflow.forecast.features.backtest.model.ModelSelection;
import ru.cashflow.forecast.features.backtest.model.QualityMetrics;
import ru.cashflow.forecast.features.forecast.model.Flows;
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

    /** Same probability threshold as GapDetector uses for scenario-only gap episodes. */
    public static final double SCENARIO_THRESHOLD = 0.3;
    public static final String NAIVE = "NAIVE_LAST_BALANCE";
    private final String primaryModel;
    private final String primaryDescription;
    private final BigDecimal buffer;
    private final ModelAccumulator primary = new ModelAccumulator();
    private final Map<String, ModelAccumulator> benchmarks = new LinkedHashMap<>();
    private final Map<String, String> descriptions = new LinkedHashMap<>();
    private final Map<String, Boolean> selectable = new LinkedHashMap<>();
    private final int[] originCells = new int[4];
    private final int[] scenarioCells = new int[4];
    private final int[] bufferCells = new int[4];
    private final List<LocalDate> evaluatedOrigins = new ArrayList<>();

    public BacktestScorer() {
        this("PRIMARY", "Primary model", Money.ZERO);
    }

    public BacktestScorer(String primaryModel, String primaryDescription, BigDecimal buffer) {
        Arrays.setAll(horizons, ignored -> new Errors());
        this.primaryModel = primaryModel;
        this.primaryDescription = primaryDescription;
        this.buffer = buffer;
    }

    public OriginResult addOrigin(LocalDate cutoff, PointForecast forecast, RiskResult risk,
            List<GapEpisode> gaps, List<DayFact> actualDays) {
        return addOrigin(cutoff, forecast, risk, gaps, actualDays, List.of());
    }

    public OriginResult addOrigin(LocalDate cutoff, PointForecast forecast, RiskResult risk,
            List<GapEpisode> gaps, List<DayFact> actualDays, List<Benchmark> references) {
        evaluatedOrigins.add(cutoff);
        var comparisons = new ArrayList<DayComparison>();
        boolean originPredicted = false;
        boolean originActual = false;
        for (var reference : references) {
            descriptions.putIfAbsent(reference.model(), reference.description());
            selectable.putIfAbsent(reference.model(), reference.selectable());
            benchmarks.computeIfAbsent(reference.model(), ignored -> new ModelAccumulator());
        }
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
            boolean predictedBreach = day.balance().signum() < 0;
            boolean actualBreach = actual.balanceEod().signum() < 0;
            originPredicted |= predictedBreach;
            originActual |= actualBreach;
            primary.add(cutoff, day.balance(), new Flows(day.inflow(), day.outflow()), actual);
            for (var reference : references) {
                benchmarks.get(reference.model()).add(cutoff, reference.balances().get(horizon),
                        reference.flows().isEmpty() ? null : reference.flows().get(horizon), actual);
            }
            if (buffer.signum() > 0) {
                count(bufferCells, day.balance().compareTo(buffer) < 0, actual.balanceEod().compareTo(buffer) < 0);
            }
            var riskDay = risk.days().get(horizon);
            if (riskDay.pBelowZero() != null) {
                count(scenarioCells, riskDay.pBelowZero() >= SCENARIO_THRESHOLD, actualBreach);
            }
            comparisons.add(new DayComparison(day.date(), horizon + 1, day.balance(), actual.balanceEod(), day.inflow(),
                    day.outflow(), actual.inflow(), actual.outflow(), riskDay.balanceP10(), riskDay.balanceP90(),
                    riskDay.pBelowZero(), predictedBreach, actualBreach));
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
        if (!comparisons.isEmpty()) {
            count(originCells, originPredicted, originActual);
        }
        return new OriginResult(cutoff, "EVALUATED", null, originErrors.mae(), originErrors.count, comparisons);
    }

    private static void count(int[] cells, boolean predicted, boolean actual) {
        cells[predicted ? (actual ? 0 : 1) : (actual ? 2 : 3)]++;
    }

    private static ConfusionMatrix matrix(BigDecimal threshold, int[] cells) {
        return ConfusionMatrix.of(threshold, cells[0], cells[1], cells[2], cells[3]);
    }

    private QualityMetrics quality() {
        ModelAccumulator naive = primaryModel.equals(NAIVE) ? primary : benchmarks.get(NAIVE);
        var scores = new ArrayList<ModelScore>();
        benchmarks.forEach((model, accumulator) -> scores.add(accumulator.score(model, descriptions.get(model), naive)));
        var notes = new ArrayList<String>();
        notes.add("Daily matrices count forecast-instance x day; overlapping origins share days and are not independent");
        notes.add("Positive class = balance below threshold (cash gap); compare accuracy with majorityBaselineAccuracy");
        notes.add("MSE is in rubles squared; RMSE is its square root in rubles");
        notes.add("skillScoreVsNaive = 1 - MAE / MAE(" + NAIVE + "); > 0 means better than keeping the cutoff balance");
        if (originCells[0] + originCells[2] == 0) {
            notes.add("No actual cash-gap day in the evaluated periods: recall and F1 for gaps are undefined, not 100%");
        }
        return new QualityMetrics(primary.score(primaryModel, primaryDescription, naive), scores,
                matrix(Money.ZERO, originCells),
                scenarioCells[0] + scenarioCells[1] + scenarioCells[2] + scenarioCells[3] == 0 ? null : matrix(Money.ZERO, scenarioCells),
                buffer.signum() > 0 ? matrix(buffer, bufferCells) : null, SCENARIO_THRESHOLD, selection(), notes);
    }

    /** Earlier two thirds of evaluated origins choose the candidate, the later third measures it. */
    private ModelSelection selection() {
        var candidates = new LinkedHashMap<String, ModelAccumulator>();
        candidates.put(primaryModel, primary);
        benchmarks.forEach((model, accumulator) -> {
            if (selectable.getOrDefault(model, false)) {
                candidates.put(model, accumulator);
            }
        });
        var origins = evaluatedOrigins.stream().distinct().sorted().toList();
        if (candidates.size() < 2 || origins.size() < 3) {
            return null;
        }
        int selectionCount = (int) Math.ceil(origins.size() * 2.0 / 3.0);
        LocalDate holdoutFrom = origins.get(selectionCount);
        var rows = new ArrayList<ModelSelection.Candidate>();
        String best = null;
        BigDecimal bestMae = null;
        for (var entry : candidates.entrySet()) {
            BigDecimal selectionMae = entry.getValue().mae(date -> date.isBefore(holdoutFrom));
            BigDecimal holdoutMae = entry.getValue().mae(date -> !date.isBefore(holdoutFrom));
            rows.add(new ModelSelection.Candidate(entry.getKey(), selectionMae, holdoutMae));
            if (selectionMae != null && (bestMae == null || selectionMae.compareTo(bestMae) < 0)) {
                best = entry.getKey();
                bestMae = selectionMae;
            }
        }
        if (best == null) {
            return null;
        }
        String chosen = best;
        BigDecimal chosenHoldout = rows.stream().filter(row -> row.model().equals(chosen)).findFirst().orElseThrow().holdoutMae();
        return new ModelSelection(chosen, holdoutFrom, selectionCount, origins.size() - selectionCount, chosenHoldout,
                primary.mae(date -> !date.isBefore(holdoutFrom)), rows);
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
                new RiskMetrics(riskCount == 0 ? null : brierSum / riskCount, riskCount, bins, ratio(coveredCount, intervalCount)),
                quality());
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

        private BigDecimal mse() {
            return count == 0 ? null : Money.cents(squared.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128));
        }

        private BigDecimal rmse() {
            return count == 0 ? null : Money.cents(squared.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128).sqrt(MathContext.DECIMAL128));
        }
    }

    /** Per-model error, flow error, gap-day classification and per-origin absolute error sums. */
    private static final class ModelAccumulator {
        private final Errors balance = new Errors();
        private final Errors inflow = new Errors();
        private final Errors outflow = new Errors();
        private BigDecimal actualAbsoluteSum = BigDecimal.ZERO;
        private final int[] cells = new int[4];
        private final Map<LocalDate, BigDecimal[]> perOrigin = new LinkedHashMap<>();

        private void add(LocalDate origin, BigDecimal forecast, Flows flows, DayFact actual) {
            BigDecimal error = forecast.subtract(actual.balanceEod());
            balance.add(error);
            actualAbsoluteSum = actualAbsoluteSum.add(actual.balanceEod().abs());
            if (flows != null) {
                inflow.add(flows.inflow().subtract(actual.inflow()));
                outflow.add(flows.outflow().subtract(actual.outflow()));
            }
            count(cells, forecast.signum() < 0, actual.balanceEod().signum() < 0);
            var sums = perOrigin.computeIfAbsent(origin, ignored -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            sums[0] = sums[0].add(error.abs());
            sums[1] = sums[1].add(BigDecimal.ONE);
        }

        private BigDecimal mae(java.util.function.Predicate<LocalDate> origins) {
            BigDecimal sum = BigDecimal.ZERO;
            BigDecimal days = BigDecimal.ZERO;
            for (var entry : perOrigin.entrySet()) {
                if (origins.test(entry.getKey())) {
                    sum = sum.add(entry.getValue()[0]);
                    days = days.add(entry.getValue()[1]);
                }
            }
            return days.signum() == 0 ? null : Money.cents(sum.divide(days, MathContext.DECIMAL128));
        }

        private ModelScore score(String model, String description, ModelAccumulator naive) {
            BigDecimal mae = balance.mae();
            BigDecimal naiveMae = naive == null ? null : naive.balance.mae();
            Double skill = mae == null || naiveMae == null || naiveMae.signum() == 0 ? null
                    : 1.0 - mae.doubleValue() / naiveMae.doubleValue();
            Double wape = balance.count == 0 || actualAbsoluteSum.signum() == 0 ? null
                    : balance.absolute.divide(actualAbsoluteSum, MathContext.DECIMAL128).doubleValue();
            return new ModelScore(model, description, balance.count, mae, balance.mse(), balance.rmse(), wape, skill,
                    inflow.mae(), outflow.mae(), matrix(Money.ZERO, cells));
        }
    }
}
