package ru.cashflow.forecast.features.recurring.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ru.cashflow.forecast.features.recurring.model.DetectionInput;
import ru.cashflow.forecast.features.recurring.model.DetectionResult;
import ru.cashflow.forecast.features.shared.model.BusinessDayRule;
import ru.cashflow.forecast.features.shared.model.Direction;
import ru.cashflow.forecast.features.shared.model.Frequency;
import ru.cashflow.forecast.features.shared.model.InputException;
import ru.cashflow.forecast.features.shared.model.Money;
import ru.cashflow.forecast.features.shared.model.PatternStatus;
import ru.cashflow.forecast.features.shared.model.Payment;
import ru.cashflow.forecast.features.shared.model.RecurringPattern;
import ru.cashflow.forecast.features.shared.model.RecurringSchedule;

@Component
public class RecurringDetector {
    public static final String VERSION = "RECURRENCE_V1";
    private final Clock clock;

    public RecurringDetector(Clock clock) { this.clock = clock; }

    public DetectionResult detect(DetectionInput input) {
        if (input.asOfDate().isAfter(LocalDate.now(clock.withZone(ZoneId.of("Europe/Moscow"))))) {
            throw new InputException("VALIDATION_FAILED", "asOfDate", "Future cutoff forbidden");
        }
        var ids = new HashSet<UUID>();
        for (var payment : input.payments()) {
            if (payment.date().isAfter(input.asOfDate()) || !ids.add(payment.paymentId()) || payment.amount().signum() <= 0) {
                throw new InputException("VALIDATION_FAILED", "payments", "Future, duplicate or invalid payment");
            }
        }
        Map<Group, List<Payment>> groups = input.payments().stream()
                .filter(payment -> payment.counterpartyKey() != null && !payment.counterpartyKey().isBlank())
                .collect(Collectors.groupingBy(payment -> new Group(payment.direction(), payment.categoryCode(), payment.counterpartyKey())));
        var patterns = new ArrayList<RecurringPattern>();
        for (var entry : groups.entrySet()) {
            var payments = entry.getValue().stream().sorted(Comparator.comparing(Payment::date).thenComparing(payment -> payment.paymentId().toString())).toList();
            if (payments.size() < 3 || payments.stream().map(Payment::date).distinct().count() != payments.size()) {
                continue;
            }
            var intervals = new ArrayList<BigDecimal>();
            for (int index = 1; index < payments.size(); index++) {
                intervals.add(BigDecimal.valueOf(ChronoUnit.DAYS.between(payments.get(index - 1).date(), payments.get(index).date())));
            }
            BigDecimal interval = Money.quantile(intervals, new BigDecimal("0.5"));
            Frequency frequency;
            BigDecimal target;
            if (within(interval, new BigDecimal("7.0"))) {
                frequency = Frequency.WEEKLY;
                target = new BigDecimal("7.0");
            } else if (within(interval, new BigDecimal("30.4"))) {
                frequency = Frequency.MONTHLY;
                target = new BigDecimal("30.4");
            } else {
                continue;
            }
            if (intervals.stream().anyMatch(value -> !within(value, target))) {
                continue;
            }
            BigDecimal typicalAmount = Money.quantile(payments.stream().map(Payment::amount).toList(), new BigDecimal("0.5"));
            if (payments.stream().anyMatch(payment -> !within(payment.amount(), typicalAmount))) {
                continue;
            }
            Integer weekday = frequency == Frequency.WEEKLY ? mode(payments.stream().map(payment -> payment.date().getDayOfWeek().getValue()).toList()) : null;
            Integer dayOfMonth = frequency == Frequency.MONTHLY ? mode(payments.stream().map(payment -> payment.date().getDayOfMonth()).toList()) : null;
            if (frequency == Frequency.WEEKLY && payments.stream().anyMatch(payment -> payment.date().getDayOfWeek().getValue() != weekday)) {
                continue;
            }
            if (frequency == Frequency.MONTHLY && payments.stream().anyMatch(payment -> Math.abs(payment.date().getDayOfMonth() - Math.min(dayOfMonth, payment.date().lengthOfMonth())) > 3)) {
                continue;
            }
            Group group = entry.getKey();
            String stableKey = input.accountId() + "\n" + group.direction() + "\n" + group.category() + "\n" + group.counterparty();
            UUID id = UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8));
            BigDecimal amount = Money.quantile(payments.subList(payments.size() - 3, payments.size()).stream().map(Payment::amount).toList(), new BigDecimal("0.5"));
            patterns.add(new RecurringPattern(id, input.accountId(), group.direction(), group.category(), group.counterparty(), PatternStatus.DETECTED,
                    new RecurringSchedule(frequency, weekday, dayOfMonth, BusinessDayRule.NONE), amount, payments.size(),
                    payments.getFirst().date(), payments.getLast().date(), input.inputDataVersion(), VERSION, 1));
        }
        patterns.sort(Comparator.comparing(pattern -> pattern.id().toString()));
        return new DetectionResult(VERSION, patterns);
    }

    private boolean within(BigDecimal value, BigDecimal target) {
        return value.subtract(target).abs().compareTo(target.multiply(new BigDecimal("0.2"))) <= 0;
    }

    private int mode(List<Integer> values) {
        var counts = new TreeMap<Integer, Integer>();
        values.forEach(value -> counts.merge(value, 1, Integer::sum));
        return counts.entrySet().stream().sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).findFirst().orElseThrow().getKey();
    }

    private record Group(Direction direction, String category, String counterparty) { }
}
