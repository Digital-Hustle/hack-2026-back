package ru.cashflow.orchestrator.features.paymentcalendar.command;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.paymentcalendar.model.PaymentCalendar.Day;
import ru.cashflow.orchestrator.features.paymentcalendar.model.PaymentCalendar.Item;
import ru.cashflow.orchestrator.features.paymentcalendar.model.PaymentCalendar.Kind;
import ru.cashflow.orchestrator.features.paymentcalendar.model.PaymentCalendar.Totals;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ExpectedPayment;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanStatus;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.Relation;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.BusinessDayRule;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Frequency;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.PatternStatus;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.RecurringPattern;

/** Expands declared schedules only; does not calculate balances, residual flows or risk. */
public final class PaymentCalendarBuilder {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final Comparator<Item> ORDER = Comparator.comparing(Item::dueDate).thenComparing(Item::id);

    private PaymentCalendarBuilder() {
    }

    public static List<Day> days(LocalDate from, LocalDate to, LocalDate today, List<ExpectedPayment> plans,
                                  List<RecurringPattern> patterns) {
        var days = new TreeMap<LocalDate, List<Item>>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            days.put(date, new ArrayList<>());
        }
        Set<Replacement> replacements = new HashSet<>();
        for (ExpectedPayment plan : plans) {
            if (plan.direction() != Direction.OUT || plan.status() == PlanStatus.CANCELLED) {
                continue;
            }
            if (plan.relation() == Relation.REPLACES_RECURRING) {
                // Settled plans still replace their occurrence; otherwise a paid commitment would reappear.
                replacements.add(new Replacement(plan.recurringPatternId(), plan.dueDate()));
            }
            if (days.containsKey(plan.dueDate())) {
                days.get(plan.dueDate()).add(planItem(plan, today));
            }
        }
        LocalDate recurringFrom = from.isAfter(today) ? from : today;
        if (!recurringFrom.isAfter(to)) {
            for (RecurringPattern pattern : patterns) {
                if (pattern.direction() != Direction.OUT || pattern.status() != PatternStatus.CONFIRMED) {
                    continue;
                }
                // FOLLOWING shifts Saturday/Sunday by at most two days, including across range/month boundaries.
                for (LocalDate nominal = recurringFrom.minusDays(2); !nominal.isAfter(to); nominal = nominal.plusDays(1)) {
                    var schedule = pattern.schedule();
                    boolean due = schedule.frequency() == Frequency.WEEKLY
                            ? nominal.getDayOfWeek().getValue() == schedule.weekday()
                            : nominal.getDayOfMonth() == Math.min(schedule.dayOfMonth(), nominal.lengthOfMonth());
                    if (!due) {
                        continue;
                    }
                    LocalDate date = nominal;
                    if (schedule.businessDayRule() == BusinessDayRule.FOLLOWING) {
                        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                            date = date.plusDays(1);
                        }
                    }
                    if (date.isBefore(recurringFrom) || date.isAfter(to)
                            || replacements.contains(new Replacement(pattern.id(), nominal))
                            || replacements.contains(new Replacement(pattern.id(), date))) {
                        continue;
                    }
                    days.get(date).add(new Item("RECURRING:" + pattern.id() + ":" + nominal, Kind.RECURRING, null,
                            pattern.id(), pattern.categoryCode(), date, nominal, pattern.categoryCode(),
                            pattern.counterpartyKey(), "CONFIRMED_RECURRING", null, "SCHEDULED", pattern.amount(),
                            null, pattern.amount(), false, pattern.version()));
                }
            }
        }
        return days.entrySet().stream().map(entry -> {
            List<Item> items = entry.getValue().stream().sorted(ORDER).toList();
            return new Day(entry.getKey(), totals(items), items);
        }).toList();
    }

    public static Item planItem(ExpectedPayment plan, LocalDate today) {
        return new Item("PLANNED:" + plan.id(), Kind.PLANNED, plan.id(), plan.recurringPatternId(), plan.title(),
                plan.dueDate(), plan.dueDate(), plan.categoryCode(), plan.counterpartyKey(), plan.source().name(),
                plan.sourceDocument(), plan.status().name(), plan.amount(), plan.settledAmount(), plan.remainingAmount(),
                plan.remainingAmount().signum() > 0 && plan.dueDate().isBefore(today), plan.version());
    }

    public static Totals totals(List<Item> items) {
        BigDecimal amount = ZERO;
        BigDecimal settled = ZERO;
        BigDecimal remaining = ZERO;
        BigDecimal overdue = ZERO;
        for (Item item : items) {
            amount = amount.add(item.amount());
            if (item.settledAmount() != null) {
                settled = settled.add(item.settledAmount());
            }
            remaining = remaining.add(item.remainingAmount());
            if (item.overdue()) {
                overdue = overdue.add(item.remainingAmount());
            }
        }
        return new Totals(amount, settled, remaining, overdue);
    }

    private record Replacement(UUID patternId, LocalDate date) {
    }
}
