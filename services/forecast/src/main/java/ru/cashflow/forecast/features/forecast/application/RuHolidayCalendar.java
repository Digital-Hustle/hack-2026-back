package ru.cashflow.forecast.features.forecast.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Russian production calendar: Monday-Friday dates on which banks do not settle (official non-working days,
 * including days off moved from weekends). Years 2023-2026 are explicit; other years fall back to the fixed
 * federal dates only, because government transfers are not predictable. Review against the official decree
 * before relying on it; the table feeds only WEEKDAY_ROBUST_V2.
 */
public class RuHolidayCalendar {
    private static final Map<Integer, Set<LocalDate>> BY_YEAR = Map.of(
            2023, dates(2023, "01-02", "01-03", "01-04", "01-05", "01-06", "02-23", "02-24", "03-08",
                    "05-01", "05-08", "05-09", "06-12", "11-06"),
            2024, dates(2024, "01-01", "01-02", "01-03", "01-04", "01-05", "01-08", "02-23", "03-08",
                    "04-29", "04-30", "05-01", "05-09", "05-10", "06-12", "11-04", "12-30", "12-31"),
            2025, dates(2025, "01-01", "01-02", "01-03", "01-06", "01-07", "01-08", "05-01", "05-02",
                    "05-08", "05-09", "06-12", "06-13", "11-03", "11-04", "12-31"),
            2026, dates(2026, "01-01", "01-02", "01-05", "01-06", "01-07", "01-08", "01-09", "02-23",
                    "03-09", "05-01", "05-11", "06-12", "11-04", "12-31"));
    private static final List<MonthDay> FIXED = List.of(MonthDay.of(1, 1), MonthDay.of(1, 2), MonthDay.of(1, 3),
            MonthDay.of(1, 4), MonthDay.of(1, 5), MonthDay.of(1, 6), MonthDay.of(1, 7), MonthDay.of(1, 8),
            MonthDay.of(2, 23), MonthDay.of(3, 8), MonthDay.of(5, 1), MonthDay.of(5, 9), MonthDay.of(6, 12),
            MonthDay.of(11, 4));

    /** True only for Monday-Friday non-working days; weekends are ordinary weekday classes for the model. */
    public boolean isNonWorkingWeekday(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        var explicit = BY_YEAR.get(date.getYear());
        return explicit != null ? explicit.contains(date) : FIXED.contains(MonthDay.from(date));
    }

    private static Set<LocalDate> dates(int year, String... monthDays) {
        return java.util.Arrays.stream(monthDays).map(value -> LocalDate.parse(year + "-" + value))
                .collect(Collectors.toUnmodifiableSet());
    }
}
