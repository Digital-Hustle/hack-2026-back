package ru.cashflow.forecast.units;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.features.forecast.application.RobustWeekdayEstimator;
import ru.cashflow.forecast.features.forecast.application.RuHolidayCalendar;
import ru.cashflow.forecast.features.forecast.model.Flows;
import ru.cashflow.forecast.features.shared.model.CalcTrace;
import ru.cashflow.forecast.features.shared.model.Money;

class RobustWeekdayEstimatorTest {
    private final RobustWeekdayEstimator estimator = new RobustWeekdayEstimator(new RuHolidayCalendar());
    private static final LocalDate FIRST_MONDAY = LocalDate.of(2025, 10, 6);

    @Test
    void holidaysAreNotSamplesAndFarOutliersAreCapped() {
        Map<LocalDate, Flows> residuals = new HashMap<>();
        for (int week = 0; week < 8; week++) {
            for (int day = 0; day < 7; day++) {
                LocalDate date = FIRST_MONDAY.plusWeeks(week).plusDays(day);
                residuals.put(date, new Flows(new BigDecimal("100.00"), Money.ZERO));
            }
        }
        // 2025-11-04 (Tuesday) is a non-working day: zero flow must not drag Tuesday down.
        residuals.put(LocalDate.of(2025, 11, 4), Flows.ZERO);
        // One far outlier on a Wednesday among otherwise varying values.
        for (int week = 0; week < 8; week++) {
            residuals.put(FIRST_MONDAY.plusWeeks(week).plusDays(2),
                    new Flows(new BigDecimal(week == 3 ? "100000.00" : (90 + week * 5) + ".00"), Money.ZERO));
        }
        var result = estimator.estimate(residuals, fallback(), CalcTrace.off());
        assertThat(result.get(DayOfWeek.TUESDAY).inflow()).isEqualTo("100.00");
        assertThat(result.get(DayOfWeek.SATURDAY).inflow()).isEqualTo("100.00");
        assertThat(result.get(DayOfWeek.WEDNESDAY).inflow().compareTo(new BigDecimal("200.00")) < 0).isTrue();
    }

    @Test
    void noisyWeekdayIsShrunkTowardTheCommonWorkdayLevel() {
        Map<LocalDate, Flows> residuals = new HashMap<>();
        String[] noisyMonday = {"0.00", "400.00", "0.00", "400.00", "0.00", "400.00", "0.00", "800.00"};
        for (int week = 0; week < 8; week++) {
            for (int day = 0; day < 7; day++) {
                LocalDate date = FIRST_MONDAY.plusWeeks(week).plusDays(day);
                BigDecimal value = day == 0 ? new BigDecimal(noisyMonday[week])
                        : new BigDecimal(day < 5 ? (95 + (week % 3) * 5) + ".00" : "0.00");
                residuals.put(date, new Flows(value, Money.ZERO));
            }
        }
        residuals.remove(LocalDate.of(2025, 11, 4));
        var trace = CalcTrace.on();
        var result = estimator.estimate(residuals, fallback(), trace);
        BigDecimal monday = result.get(DayOfWeek.MONDAY).inflow();
        assertThat(monday.compareTo(new BigDecimal("250.00")) < 0).isTrue();
        assertThat(monday.compareTo(new BigDecimal("100.00")) > 0).isTrue();
        assertThat(result.get(DayOfWeek.SUNDAY).inflow()).isEqualTo("0.00");
        assertThat(trace.steps()).hasSize(2);
    }

    private static Map<DayOfWeek, Flows> fallback() {
        var result = new EnumMap<DayOfWeek, Flows>(DayOfWeek.class);
        for (var weekday : DayOfWeek.values()) {
            result.put(weekday, Flows.ZERO);
        }
        return result;
    }
}
