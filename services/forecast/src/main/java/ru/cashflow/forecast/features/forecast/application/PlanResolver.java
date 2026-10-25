package ru.cashflow.forecast.features.forecast.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ru.cashflow.forecast.features.forecast.model.Flows;
import ru.cashflow.forecast.features.forecast.model.ForecastInput;
import ru.cashflow.forecast.features.forecast.model.ResidualHistory;
import ru.cashflow.forecast.features.forecast.model.ResolvedCalendar;
import ru.cashflow.forecast.features.forecast.model.StreamKey;
import ru.cashflow.forecast.features.forecast.model.TrainingHistory;
import ru.cashflow.forecast.features.shared.model.Direction;
import ru.cashflow.forecast.features.shared.model.InputException;
import ru.cashflow.forecast.features.shared.model.PlanRelation;
import ru.cashflow.forecast.features.shared.model.PatternStatus;
import ru.cashflow.forecast.features.shared.model.Warning;

@Component
public class PlanResolver {
    private final RecurringCalendar calendar;

    public PlanResolver(RecurringCalendar calendar) { this.calendar = calendar; }

    public ResolvedCalendar resolve(ForecastInput input, TrainingHistory history, ResidualHistory unplannedResiduals) {
        LocalDate from = input.asOfDate().plusDays(1);
        LocalDate to = input.asOfDate().plusDays(30);
        var occurrences = new ArrayList<>(calendar.occurrences(input.recurringPatterns(), from, to));
        var occurrencesByPattern = occurrences.stream().collect(Collectors.groupingBy(item -> item.patternId()));
        Map<LocalDate, Flows> planned = new HashMap<>();
        Map<LocalDate, Flows> recurring = new HashMap<>();
        Set<StreamKey> replacements = new HashSet<>();
        Set<String> replacedOccurrences = new HashSet<>();
        var warnings = new ArrayList<Warning>();
        for (var plan : input.knownPlans().stream().sorted(java.util.Comparator.comparing(item -> item.id().toString())).toList()) {
            if (!plan.dueDate().isAfter(input.asOfDate())) {
                warnings.add(new Warning("UNMATCHED_PLAN", "Overdue plan excluded; no automatic date shift", plan.dueDate()));
                continue;
            }
            if (plan.dueDate().isAfter(to)) {
                continue;
            }
            if (plan.relation() == PlanRelation.REPLACES_RECURRING) {
                var matching = occurrencesByPattern.getOrDefault(plan.recurringPatternId(), java.util.List.of()).stream().filter(item ->
                        Objects.equals(item.patternId(), plan.recurringPatternId()) && item.direction() == plan.direction()
                                && item.categoryCode().equals(plan.categoryCode()) && item.counterpartyKey() != null
                                && Objects.equals(item.counterpartyKey(), plan.counterpartyKey())
                                && (item.date().equals(plan.dueDate()) || item.nominalDate().equals(plan.dueDate()))).toList();
                if (matching.size() != 1) {
                    throw new InputException("UNRESOLVED_PLAN", "knownPlans", "No unique recurring instance for replacement");
                }
                var occurrence = matching.getFirst();
                String key = occurrence.patternId() + ":" + occurrence.nominalDate();
                if (!replacedOccurrences.add(key)) {
                    throw new InputException("CONFLICT", "knownPlans", "Two plans replace the same recurring instance");
                }
                occurrences.remove(occurrence);
            } else if (plan.relation() == PlanRelation.REPLACES_BASELINE) {
                StreamKey key = new StreamKey(plan.direction(), plan.baselineCounterpartyKey());
                boolean linked = history.payments().stream().anyMatch(payment -> payment.direction() == key.direction()
                        && Objects.equals(payment.counterpartyKey(), key.counterpartyKey())
                        && !unplannedResiduals.excludedPaymentIds().contains(payment.paymentId())
                        && unplannedResiduals.days().containsKey(payment.date()));
                if (!linked) {
                    throw new InputException("UNRESOLVED_PLAN", "knownPlans.baselineCounterpartyKey", "Replacement has no observable residual stream");
                }
                replacements.add(key);
            }
            add(planned, plan.dueDate(), plan.direction(), plan.remainingAmount());
        }
        var activePatterns = input.recurringPatterns().stream()
                .filter(p -> p.status() == PatternStatus.CONFIRMED)
                .map(p -> p.id()).collect(Collectors.toSet());
        occurrences.stream().filter(item -> activePatterns.contains(item.patternId()))
                .forEach(item -> add(recurring, item.date(), item.direction(), item.amount()));
        if (!planned.isEmpty()) {
            warnings.add(new Warning("ASSUMED_FIXED_PLAN_DATES", "Known plan dates and unpaid amounts treated as fixed", null));
        }
        return new ResolvedCalendar(planned, recurring, replacements, warnings);
    }

    private void add(Map<LocalDate, Flows> map, LocalDate date, Direction direction, BigDecimal amount) {
        var current = map.getOrDefault(date, Flows.ZERO);
        map.put(date, direction == Direction.IN ? new Flows(current.inflow().add(amount), current.outflow())
                : new Flows(current.inflow(), current.outflow().add(amount)));
    }
}
