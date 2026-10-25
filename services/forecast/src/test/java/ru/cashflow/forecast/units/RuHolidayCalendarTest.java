package ru.cashflow.forecast.units;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.features.forecast.application.RuHolidayCalendar;

class RuHolidayCalendarTest {
    private final RuHolidayCalendar calendar = new RuHolidayCalendar();

    @Test
    void explicitYearsIncludeTransferredDaysOffAndExcludeWeekends() {
        assertThat(calendar.isNonWorkingWeekday(LocalDate.of(2026, 1, 9))).isTrue();
        assertThat(calendar.isNonWorkingWeekday(LocalDate.of(2026, 3, 9))).isTrue();
        assertThat(calendar.isNonWorkingWeekday(LocalDate.of(2025, 11, 3))).isTrue();
        assertThat(calendar.isNonWorkingWeekday(LocalDate.of(2025, 11, 4))).isTrue();
        assertThat(calendar.isNonWorkingWeekday(LocalDate.of(2024, 4, 29))).isTrue();
        assertThat(calendar.isNonWorkingWeekday(LocalDate.of(2026, 1, 10))).isFalse();
        assertThat(calendar.isNonWorkingWeekday(LocalDate.of(2026, 3, 10))).isFalse();
        assertThat(calendar.isNonWorkingWeekday(LocalDate.of(2025, 3, 10))).isFalse();
    }

    @Test
    void otherYearsFallBackToFixedFederalDates() {
        assertThat(calendar.isNonWorkingWeekday(LocalDate.of(2027, 3, 8))).isTrue();
        assertThat(calendar.isNonWorkingWeekday(LocalDate.of(2027, 3, 9))).isFalse();
    }
}
