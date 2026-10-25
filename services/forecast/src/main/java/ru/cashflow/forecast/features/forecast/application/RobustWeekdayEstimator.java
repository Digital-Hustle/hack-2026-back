package ru.cashflow.forecast.features.forecast.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import ru.cashflow.forecast.features.forecast.model.Flows;
import ru.cashflow.forecast.features.shared.model.CalcTrace;
import ru.cashflow.forecast.features.shared.model.Money;

/**
 * Residual weekday level for WEEKDAY_ROBUST_V2. Per direction and weekday: non-working holidays are not samples,
 * values beyond the Tukey far fence (3 IQR) are capped, and Monday-Friday means are shrunk toward their common level
 * by an empirical-Bayes factor B = se2 / (se2 + tau2), where tau2 is the estimated between-weekday variance.
 * Weekend classes are not pooled. A weekday without usable samples falls back to the plain mean.
 */
public final class RobustWeekdayEstimator {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal THREE = BigDecimal.valueOf(3);
    private final RuHolidayCalendar holidays;

    public RobustWeekdayEstimator(RuHolidayCalendar holidays) {
        this.holidays = holidays;
    }

    public Map<DayOfWeek, Flows> estimate(Map<LocalDate, Flows> residuals, Map<DayOfWeek, Flows> fallback, CalcTrace trace) {
        var inflow = estimateDirection(residuals, fallback, true, trace);
        var outflow = estimateDirection(residuals, fallback, false, trace);
        var result = new EnumMap<DayOfWeek, Flows>(DayOfWeek.class);
        for (var weekday : DayOfWeek.values()) {
            result.put(weekday, new Flows(inflow.get(weekday), outflow.get(weekday)));
        }
        return result;
    }

    private Map<DayOfWeek, BigDecimal> estimateDirection(Map<LocalDate, Flows> residuals,
            Map<DayOfWeek, Flows> fallback, boolean inflow, CalcTrace trace) {
        var samples = new EnumMap<DayOfWeek, List<BigDecimal>>(DayOfWeek.class);
        for (var weekday : DayOfWeek.values()) {
            samples.put(weekday, new ArrayList<>());
        }
        int skippedHolidays = 0;
        for (var entry : residuals.entrySet()) {
            if (holidays.isNonWorkingWeekday(entry.getKey())) {
                skippedHolidays++;
                continue;
            }
            samples.get(entry.getKey().getDayOfWeek()).add(inflow ? entry.getValue().inflow() : entry.getValue().outflow());
        }
        var level = new EnumMap<DayOfWeek, BigDecimal>(DayOfWeek.class);
        var variance = new EnumMap<DayOfWeek, BigDecimal>(DayOfWeek.class);
        var capped = new EnumMap<DayOfWeek, Integer>(DayOfWeek.class);
        for (var weekday : DayOfWeek.values()) {
            var values = samples.get(weekday);
            if (values.isEmpty()) {
                continue;
            }
            int[] cappedCount = new int[1];
            var clean = cap(values, cappedCount);
            capped.put(weekday, cappedCount[0]);
            level.put(weekday, mean(clean));
            variance.put(weekday, sampleVariance(clean, level.get(weekday)));
        }
        var pooled = new ArrayList<DayOfWeek>();
        for (var weekday : DayOfWeek.values()) {
            if (weekday != DayOfWeek.SATURDAY && weekday != DayOfWeek.SUNDAY && level.containsKey(weekday)
                    && samples.get(weekday).size() >= 2) {
                pooled.add(weekday);
            }
        }
        BigDecimal grand = null;
        BigDecimal tau2 = null;
        if (pooled.size() >= 3) {
            grand = mean(pooled.stream().map(level::get).toList());
            BigDecimal between = sampleVariance(pooled.stream().map(level::get).toList(), grand);
            BigDecimal meanSe2 = mean(pooled.stream().map(day ->
                    variance.get(day).divide(BigDecimal.valueOf(samples.get(day).size()), MC)).toList());
            tau2 = between.subtract(meanSe2).max(BigDecimal.ZERO);
        }
        var result = new EnumMap<DayOfWeek, BigDecimal>(DayOfWeek.class);
        var shrink = new StringBuilder();
        for (var weekday : DayOfWeek.values()) {
            var own = level.get(weekday);
            if (own == null) {
                result.put(weekday, inflow ? fallback.get(weekday).inflow() : fallback.get(weekday).outflow());
                continue;
            }
            BigDecimal estimate = own;
            if (tau2 != null && pooled.contains(weekday)) {
                BigDecimal se2 = variance.get(weekday).divide(BigDecimal.valueOf(samples.get(weekday).size()), MC);
                BigDecimal denominator = se2.add(tau2);
                BigDecimal factor = denominator.signum() == 0 ? BigDecimal.ZERO : se2.divide(denominator, MC);
                estimate = own.add(factor.multiply(grand.subtract(own), MC), MC);
                shrink.append(weekday.name(), 0, 3).append('=').append(factor.setScale(3, java.math.RoundingMode.HALF_EVEN)).append(' ');
            }
            result.put(weekday, Money.cents(estimate.max(BigDecimal.ZERO)));
        }
        if (trace.enabled()) {
            var data = new java.util.LinkedHashMap<String, String>();
            for (var weekday : DayOfWeek.values()) {
                data.put(weekday.name(), "n=" + samples.get(weekday).size() + " mean=" + (level.get(weekday) == null ? "-"
                        : Money.cents(level.get(weekday)).toPlainString()) + " capped=" + capped.getOrDefault(weekday, 0)
                        + " final=" + result.get(weekday).toPlainString());
            }
            data.put("holidaysExcluded", String.valueOf(skippedHolidays));
            data.put("tau2", tau2 == null ? "n/a" : tau2.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString());
            data.put("shrinkage", shrink.length() == 0 ? "none" : shrink.toString().trim());
            trace.step("RESIDUAL_LEVEL", "Robust weekday level (" + (inflow ? "inflow" : "outflow") + ")", data);
        }
        return result;
    }

    private List<BigDecimal> cap(List<BigDecimal> values, int[] cappedCount) {
        if (values.size() < 5) {
            return values;
        }
        BigDecimal q1 = Money.quantile(values, new BigDecimal("0.25"));
        BigDecimal q3 = Money.quantile(values, new BigDecimal("0.75"));
        BigDecimal iqr = q3.subtract(q1);
        if (iqr.signum() <= 0) {
            return values;
        }
        BigDecimal upper = q3.add(iqr.multiply(THREE));
        BigDecimal lower = q1.subtract(iqr.multiply(THREE));
        var result = new ArrayList<BigDecimal>(values.size());
        for (var value : values) {
            if (value.compareTo(upper) > 0) {
                result.add(upper);
                cappedCount[0]++;
            } else if (value.compareTo(lower) < 0) {
                result.add(lower);
                cappedCount[0]++;
            } else {
                result.add(value);
            }
        }
        return result;
    }

    private BigDecimal mean(List<BigDecimal> values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (var value : values) {
            sum = sum.add(value);
        }
        return sum.divide(BigDecimal.valueOf(values.size()), MC);
    }

    private BigDecimal sampleVariance(List<BigDecimal> values, BigDecimal mean) {
        if (values.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (var value : values) {
            BigDecimal delta = value.subtract(mean);
            sum = sum.add(delta.multiply(delta, MC), MC);
        }
        return sum.divide(BigDecimal.valueOf(values.size() - 1L), MC);
    }
}
