package ru.cashflow.forecast.units;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.features.forecast.application.RecurringCalendar;
import ru.cashflow.forecast.features.shared.model.BusinessDayRule;
import ru.cashflow.forecast.features.shared.model.Direction;
import ru.cashflow.forecast.features.shared.model.Frequency;
import ru.cashflow.forecast.features.shared.model.PatternStatus;
import ru.cashflow.forecast.features.shared.model.RecurringPattern;
import ru.cashflow.forecast.features.shared.model.RecurringSchedule;
import static org.assertj.core.api.Assertions.assertThat;

class RecurringCalendarTest {
    private final RecurringCalendar calendar = new RecurringCalendar();

    @Test
    void capsMonthEndBeforeMovingWeekendAndIncludesCarryFromPriorMonth() {
        var pattern = pattern(Frequency.MONTHLY, null, 31, BusinessDayRule.FOLLOWING);
        var result = calendar.occurrences(List.of(pattern), LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        assertThat(result).extracting(item -> item.date()).containsExactly(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 31));
        assertThat(result.get(0).nominalDate()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void respectsLeapDayAndWeeklyFollowingBoundaries() {
        var monthly = pattern(Frequency.MONTHLY, null, 31, BusinessDayRule.NONE);
        assertThat(calendar.occurrences(List.of(monthly), LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29)))
                .extracting(item -> item.date()).containsExactly(LocalDate.of(2028, 2, 29));
        var weekly = pattern(Frequency.WEEKLY, 7, null, BusinessDayRule.FOLLOWING);
        assertThat(calendar.occurrences(List.of(weekly), LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)))
                .extracting(item -> item.date()).containsExactly(LocalDate.of(2026, 10, 26));
    }

    static RecurringPattern pattern(Frequency frequency, Integer weekday, Integer monthDay, BusinessDayRule rule) {
        return new RecurringPattern(UUID.fromString("66666666-6666-4666-8666-666666666666"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"), Direction.OUT, "RENT", "landlord", PatternStatus.CONFIRMED,
                new RecurringSchedule(frequency, weekday, monthDay, rule), new BigDecimal("100.00"), 3,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 19), 42, "RECURRENCE_V1", 1);
    }
}
