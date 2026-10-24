package ru.cashflow.orchestrator.units;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.paymentcalendar.command.PaymentCalendarBuilder;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.BusinessDayRule;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Frequency;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.PatternStatus;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.RecurringPattern;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Schedule;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.StatusSource;

class PaymentCalendarBuilderTest {

    @Test
    void clampsMonthlyDay31ToLeapDayAndPreservesMoneyBeyondDoublePrecision() {
        LocalDate date = LocalDate.parse("2028-02-29");
        var pattern = pattern(new Schedule(Frequency.MONTHLY, null, 31, BusinessDayRule.NONE), "99999999999999999.99");
        var days = PaymentCalendarBuilder.days(date, date, date, List.of(), List.of(pattern));
        assertThat(days).hasSize(1);
        assertThat(days.getFirst().items()).hasSize(1);
        assertThat(days.getFirst().items().getFirst().nominalDate()).isEqualTo(date);
        assertThat(days.getFirst().totals().remainingAmount()).isEqualByComparingTo("99999999999999999.99");
    }

    @Test
    void includesSaturdayShiftedIntoMondayAtRangeStartAndKeepsOccurrenceIdStable() {
        LocalDate monday = LocalDate.parse("2026-03-02");
        var pattern = pattern(new Schedule(Frequency.WEEKLY, 6, null, BusinessDayRule.FOLLOWING), "0.10");
        var oneDay = PaymentCalendarBuilder.days(monday, monday, monday, List.of(), List.of(pattern));
        var fullWeek = PaymentCalendarBuilder.days(monday.minusDays(2), monday.plusDays(4), monday,
                List.of(), List.of(pattern));
        var item = oneDay.getFirst().items().getFirst();
        assertThat(item.nominalDate()).isEqualTo(monday.minusDays(2));
        assertThat(item.dueDate()).isEqualTo(monday);
        assertThat(item.id()).isEqualTo(fullWeek.get(2).items().getFirst().id());
        assertThat(item.settledAmount()).isNull();
        assertThat(item.overdue()).isFalse();
    }

    @Test
    void doesNotGeneratePastRecurringDebtsOrShiftNoneRule() {
        LocalDate monday = LocalDate.parse("2026-03-02");
        var pattern = pattern(new Schedule(Frequency.WEEKLY, 6, null, BusinessDayRule.NONE), "0.20");
        var days = PaymentCalendarBuilder.days(monday.minusDays(2), monday.plusDays(5), monday,
                List.of(), List.of(pattern));
        assertThat(days.getFirst().items()).isEmpty();
        assertThat(days.getLast().items()).hasSize(1);
        assertThat(days.getLast().items().getFirst().dueDate()).isEqualTo(monday.plusDays(5));
        assertThat(PaymentCalendarBuilder.totals(days.stream().flatMap(day -> day.items().stream()).toList())
                .remainingAmount()).isEqualByComparingTo("0.20");
    }

    private static RecurringPattern pattern(Schedule schedule, String amount) {
        UUID id = UUID.randomUUID();
        return new RecurringPattern(id, UUID.randomUUID(), UUID.randomUUID(), id.toString(), Direction.OUT,
                "RENT", "supplier", PatternStatus.CONFIRMED, StatusSource.USER, schedule, new BigDecimal(amount),
                3, LocalDate.parse("2025-01-01"), LocalDate.parse("2026-01-01"), "TEST", 1,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), 0);
    }
}
