package ru.cashflow.forecast.features.forecast.application;

import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ru.cashflow.forecast.features.forecast.model.Flows;
import ru.cashflow.forecast.features.forecast.model.ForecastInput;
import ru.cashflow.forecast.features.forecast.model.ResidualHistory;
import ru.cashflow.forecast.features.forecast.model.StreamKey;
import ru.cashflow.forecast.features.shared.model.DayFact;
import ru.cashflow.forecast.features.shared.model.Direction;
import ru.cashflow.forecast.features.shared.model.InputException;
import ru.cashflow.forecast.features.shared.model.Payment;
import ru.cashflow.forecast.features.shared.model.PlanRelation;

@Component
public class ResidualExtractor {
    private final RecurringCalendar calendar;

    public ResidualExtractor(RecurringCalendar calendar) { this.calendar = calendar; }

    public ResidualHistory extract(ForecastInput input, List<DayFact> days, List<Payment> payments,
            Set<StreamKey> baselineReplacements, boolean scoring) {
        Map<LocalDate, Flows> residuals = new HashMap<>();
        days.stream().filter(DayFact::complete).forEach(day -> residuals.put(day.date(), new Flows(day.inflow(), day.outflow())));
        Set<UUID> excluded = new HashSet<>();
        if (days.isEmpty()) {
            return new ResidualHistory(residuals, excluded);
        }
        LocalDate from = days.stream().map(DayFact::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = days.stream().map(DayFact::date).max(LocalDate::compareTo).orElseThrow();
        Map<Group, List<Payment>> groups = payments.stream().collect(Collectors.groupingBy(payment ->
                new Group(payment.direction(), payment.categoryCode(), payment.counterpartyKey())));
        // Explicit payroll replaces its historical economic group, including after stopping the rule.
        // Salary changes and split payments must not leak old wages into the statistical baseline.
        // Generic categories can represent imported payroll for the explicitly identified payee.
        // Do not infer salary from transactions classified in an unrelated specific category.
        Set<String> genericCategories = Set.of("OTHER", "UNKNOWN", "UNCATEGORIZED", "UNCLASSIFIED");
        for (var pattern : input.recurringPatterns()) {
            if (pattern.employeePayment()) {
                groups.forEach((group, rows) -> {
                    String category = group.category();
                    if (group.direction() == pattern.direction()
                            && Objects.equals(group.counterparty(), pattern.counterpartyKey())
                            && (Objects.equals(category, pattern.categoryCode())
                                    || category != null && genericCategories.contains(category))) {
                        rows.forEach(payment -> excluded.add(payment.paymentId()));
                    }
                });
            }
        }
        for (var occurrence : calendar.occurrences(input.recurringPatterns().stream()
                .filter(p -> !p.employeePayment()).toList(), from, to)) {
            boolean replaced = scoring && input.knownPlans().stream().anyMatch(plan -> plan.relation() == PlanRelation.REPLACES_RECURRING
                    && Objects.equals(plan.recurringPatternId(), occurrence.patternId())
                    && (plan.dueDate().equals(occurrence.date()) || plan.dueDate().equals(occurrence.nominalDate())));
            if (!replaced) {
                matchAndExclude(groups.getOrDefault(new Group(occurrence.direction(), occurrence.categoryCode(), occurrence.counterpartyKey()), List.of()),
                        occurrence.date(), occurrence.amount(), 3, excluded);
            }
        }
        if (scoring) {
            for (var plan : input.knownPlans()) {
                Set<LocalDate> dates = new HashSet<>();
                dates.add(plan.dueDate());
                if (plan.relation() == PlanRelation.REPLACES_RECURRING) {
                    var occurrence = calendar.occurrences(input.recurringPatterns(), input.asOfDate().plusDays(1), input.asOfDate().plusDays(30))
                            .stream().filter(item -> Objects.equals(item.patternId(), plan.recurringPatternId())
                                    && (plan.dueDate().equals(item.date()) || plan.dueDate().equals(item.nominalDate()))).findFirst();
                    if (occurrence.isPresent()) {
                        dates.add(occurrence.get().date());
                    }
                }
                if (dates.stream().anyMatch(date -> !date.isBefore(from) && !date.isAfter(to) && date.isAfter(input.asOfDate()))) {
                    var candidates = groups.getOrDefault(new Group(plan.direction(), plan.categoryCode(), plan.counterpartyKey()), List.of())
                            .stream().filter(payment -> dates.contains(payment.date())
                                    && !excluded.contains(payment.paymentId())).toList();
                    matchAndExclude(candidates, null, plan.remainingAmount(), 0, excluded);
                }
            }
        }
        for (var payment : payments) {
            if (excluded.contains(payment.paymentId()) || baselineReplacements.contains(new StreamKey(payment.direction(), payment.counterpartyKey()))) {
                excluded.add(payment.paymentId());
                var flows = residuals.get(payment.date());
                if (flows != null) {
                    BigDecimal inflow = flows.inflow();
                    BigDecimal outflow = flows.outflow();
                    if (payment.direction() == Direction.IN) {
                        inflow = inflow.subtract(payment.amount());
                    } else {
                        outflow = outflow.subtract(payment.amount());
                    }
                    if (inflow.signum() < 0 || outflow.signum() < 0) {
                        throw new InputException("VALIDATION_FAILED", "payments", "Matched payments exceed complete daily flow");
                    }
                    residuals.put(payment.date(), new Flows(inflow, outflow));
                }
            }
        }
        return new ResidualHistory(residuals, excluded);
    }

    private void matchAndExclude(List<Payment> candidates, LocalDate date, BigDecimal amount, int dateTolerance, Set<UUID> excluded) {
        var matched = candidates.stream().filter(payment -> (date == null || Math.abs(ChronoUnit.DAYS.between(date, payment.date())) <= dateTolerance)
                && payment.amount().subtract(amount).abs().compareTo(amount.multiply(new BigDecimal("0.2"))) <= 0).toList();
        if (matched.size() > 1 || matched.size() == 1 && !excluded.add(matched.getFirst().paymentId())) {
            throw new InputException("UNRESOLVED_PLAN", "payments", "Ambiguous economic payment match");
        }
    }

    private record Group(Direction direction, String category, String counterparty) { }
}
