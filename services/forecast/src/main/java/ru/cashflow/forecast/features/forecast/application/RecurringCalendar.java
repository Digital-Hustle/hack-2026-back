package ru.cashflow.forecast.features.forecast.application;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import ru.cashflow.forecast.features.forecast.model.Occurrence;
import ru.cashflow.forecast.features.shared.model.BusinessDayRule;
import ru.cashflow.forecast.features.shared.model.Frequency;
import ru.cashflow.forecast.features.shared.model.RecurringPattern;

@Component
public class RecurringCalendar {
    public List<Occurrence> occurrences(List<RecurringPattern> patterns, LocalDate from, LocalDate to) {
        var result = new ArrayList<Occurrence>();
        for (var pattern : patterns) {
            var schedule = pattern.schedule();
            for (LocalDate date = from.minusDays(2); !date.isAfter(to); date = date.plusDays(1)) {
                boolean due = schedule.frequency() == Frequency.WEEKLY
                        ? date.getDayOfWeek().getValue() == schedule.weekday()
                        : date.getDayOfMonth() == Math.min(schedule.dayOfMonth(), date.lengthOfMonth());
                if (!due) {
                    continue;
                }
                LocalDate shifted = date;
                if (schedule.businessDayRule() == BusinessDayRule.FOLLOWING) {
                    while (shifted.getDayOfWeek() == DayOfWeek.SATURDAY || shifted.getDayOfWeek() == DayOfWeek.SUNDAY) {
                        shifted = shifted.plusDays(1);
                    }
                }
                if (!shifted.isBefore(from) && !shifted.isAfter(to)) {
                    result.add(new Occurrence(pattern.id(), date, shifted, pattern.direction(), pattern.amount(), pattern.categoryCode(), pattern.counterpartyKey()));
                }
            }
        }
        result.sort(Comparator.comparing(Occurrence::date).thenComparing(item -> item.patternId().toString()));
        return List.copyOf(result);
    }
}
