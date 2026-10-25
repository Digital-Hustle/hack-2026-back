package ru.cashflow.forecast.features.forecast.application;

import org.springframework.stereotype.Component;

import ru.cashflow.forecast.features.forecast.model.ForecastInput;
import ru.cashflow.forecast.features.shared.model.Completeness;
import ru.cashflow.forecast.features.shared.model.DailyStatus;
import ru.cashflow.forecast.features.shared.model.DayFact;
import ru.cashflow.forecast.features.shared.model.Direction;
import ru.cashflow.forecast.features.shared.model.ForecastScope;
import ru.cashflow.forecast.features.shared.model.Frequency;
import ru.cashflow.forecast.features.shared.model.InputException;
import ru.cashflow.forecast.features.shared.model.Money;
import ru.cashflow.forecast.features.shared.model.PatternStatus;
import ru.cashflow.forecast.features.shared.model.Payment;
import ru.cashflow.forecast.features.shared.model.PlanRelation;
import ru.cashflow.forecast.features.shared.model.PlanStatus;
import ru.cashflow.forecast.features.shared.model.Settings;
import ru.cashflow.forecast.features.shared.model.RecurringPattern;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Component
public class SnapshotValidator {
    private final Clock clock;

    public SnapshotValidator(Clock clock) {
        this.clock = clock;
    }

    public void validate(ForecastInput input) {
        validateSettings(input.settings());
        require(
                input.settings().algorithm() != null
                        && input.settings().algorithm().name().equals(input.algorithmVersion()),
                "algorithmVersion",
                "Unsupported algorithm version");
        require(
                !input.asOfDate().isAfter(LocalDate.now(clock.withZone(zone(input.timezone())))),
                "asOfDate",
                "Future cutoff forbidden");
        require(
                !input.historyFrom().isAfter(input.asOfDate()),
                "historyFrom",
                "History starts after cutoff");
        require(
                !input.startBalanceDate().isAfter(input.asOfDate()),
                "startBalanceDate",
                "Future balance anchor forbidden");
        require(
                input.knowledgeCutoff() != null
                        && !input.knowledgeCutoff().isAfter(clock.instant()),
                "knowledgeCutoff",
                "Knowledge cutoff must be present and cannot be in the future");
        validateDays(
                input.dailyHistory(),
                input.payments(),
                input.historyFrom(),
                input.asOfDate(),
                false);
        if (input.startBalanceDate().isBefore(input.asOfDate())) {
            // B(T) is already supplied. Check visible contradictions only; provenance before
            // historyFrom belongs to the builder.
            boolean gap =
                    input.dailyHistory().stream()
                            .anyMatch(
                                    day ->
                                            day.date().isAfter(input.startBalanceDate())
                                                    && !day.complete());
            if (gap) {
                throw new InputException(
                        "BALANCE_ANCHOR_REQUIRED",
                        "startBalanceDate",
                        "Unknown period after balance anchor");
            }
        }
        require(
                input.scope() != ForecastScope.HISTORY_ONLY || input.knownPlans().isEmpty(),
                "knownPlans",
                "Plans forbidden in history-only scope");
        unique(input.knownPlans(), plan -> plan.id(), "knownPlans");
        unique(input.recurringPatterns(), pattern -> pattern.id(), "recurringPatterns");
        unique(input.completeRanges(), range -> range.id(), "completeRanges");
        var cutoff = input.knowledgeCutoff();
        for (var plan : input.knownPlans()) {
            require(
                    plan.companyId().equals(input.companyId())
                            && plan.accountId().equals(input.accountId()),
                    "knownPlans",
                    "Plan scope mismatch");
            require(
                    plan.knownAt().isBefore(cutoff)
                            && plan.createdAt().isBefore(cutoff)
                            && plan.updatedAt().isBefore(cutoff),
                    "knownPlans",
                    "Late plan state forbidden");
            require(
                    !plan.createdAt().isAfter(plan.updatedAt())
                            && !plan.knownAt().isAfter(plan.updatedAt()),
                    "knownPlans",
                    "Plan timestamps inconsistent");
            require(
                    plan.amount().subtract(plan.settledAmount()).compareTo(plan.remainingAmount())
                                    == 0
                            && plan.settledAmount().signum() >= 0
                            && plan.remainingAmount().signum() > 0,
                    "knownPlans",
                    "Invalid plan remainder");
            require(
                    plan.status() == PlanStatus.OPEN && plan.settledAmount().signum() == 0
                            || plan.status() == PlanStatus.PARTIALLY_SETTLED
                                    && plan.settledAmount().signum() > 0,
                    "knownPlans",
                    "Inactive or inconsistent plan state");
            require(
                    plan.overdue() == plan.dueDate().isBefore(input.asOfDate()),
                    "knownPlans",
                    "Invalid overdue flag");
            require(
                    plan.relation() == PlanRelation.REPLACES_RECURRING
                            ? plan.recurringPatternId() != null
                                    && plan.baselineCounterpartyKey() == null
                            : plan.recurringPatternId() == null,
                    "knownPlans",
                    "Invalid recurring relation");
            require(
                    plan.relation() == PlanRelation.REPLACES_BASELINE
                            ? plan.baselineCounterpartyKey() != null
                                    && !plan.baselineCounterpartyKey().isBlank()
                            : plan.baselineCounterpartyKey() == null,
                    "knownPlans",
                    "Invalid baseline relation");
        }
        Set<String> groups = new HashSet<>();
        for (var pattern : input.recurringPatterns()) {
            require(
                    pattern.accountId().equals(input.accountId())
                            && (pattern.status() == PatternStatus.CONFIRMED
                                    || pattern.employeePayment() && pattern.status() == PatternStatus.EXCLUDED)
                            && (!pattern.employeePayment()
                                    || pattern.direction() == Direction.OUT
                                            && pattern.counterpartyKey() != null),
                    "recurringPatterns",
                    "Only confirmed calendars or stopped employee payments of this account allowed");
            require(
                    (manualPattern(pattern) || observedPattern(pattern, input.asOfDate()))
                            && pattern.inputDataVersion() <= input.inputDataVersion(),
                    "recurringPatterns",
                    "Future pattern state forbidden");
            var schedule = pattern.schedule();
            boolean weekly = schedule.frequency() == Frequency.WEEKLY;
            require(
                    weekly
                            ? schedule.weekday() != null
                                    && schedule.weekday() >= 1
                                    && schedule.weekday() <= 7
                                    && schedule.dayOfMonth() == null
                            : schedule.dayOfMonth() != null
                                    && schedule.dayOfMonth() >= 1
                                    && schedule.dayOfMonth() <= 31
                                    && schedule.weekday() == null,
                    "recurringPatterns.schedule",
                    "Invalid schedule fields");
            require(
                    groups.add(
                            pattern.direction()
                                    + ":"
                                    + pattern.categoryCode()
                                    + ":"
                                    + pattern.counterpartyKey()),
                    "recurringPatterns",
                    "Ambiguous confirmed group");
        }
        var ranges =
                input.completeRanges().stream()
                        .sorted(java.util.Comparator.comparing(range -> range.fromDate()))
                        .toList();
        LocalDate previousEnd = null;
        for (var range : ranges) {
            require(
                    range.accountId().equals(input.accountId())
                            && !range.fromDate().isAfter(range.toDate())
                            && range.createdAt().isBefore(cutoff),
                    "completeRanges",
                    "Invalid completeness range");
            require(
                    previousEnd == null || range.fromDate().isAfter(previousEnd),
                    "completeRanges",
                    "Ranges must be cutoff-resolved without overlaps");
            previousEnd = range.toDate();
            for (var day : input.dailyHistory()) {
                if (!day.date().isBefore(range.fromDate()) && !day.date().isAfter(range.toDate())) {
                    require(
                            day.complete()
                                    == (range.completeness() == Completeness.CONFIRMED_COMPLETE),
                            "completeRanges",
                            "Completeness contradicts daily status");
                }
            }
        }
    }

    public void validateSettings(Settings settings) {
        require(
                Set.of(8, 10, 12).contains(settings.windowWeeks())
                        && settings.horizonDays() == 30
                        && settings.bufferAmount().signum() >= 0
                        && settings.scenarioCount() >= 100
                        && settings.scenarioCount() <= 5000
                        && settings.minCalibrationOrigins() >= 12
                        && settings.minCalibrationOrigins() <= 100
                        && settings.calibrationStepDays() == 7
                        && settings.algorithm() != null,
                "settings",
                "Unsupported settings");
    }

    public static ZoneId zone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (java.time.DateTimeException | NullPointerException exception) {
            throw new InputException(
                    "VALIDATION_FAILED", "timezone", "Valid company timezone required");
        }
    }

    public static void validateHistoricalCutoff(ForecastInput input) {
        if (input.knowledgeCutoff() == null
                || input.knowledgeCutoff()
                        .isAfter(
                                input.asOfDate()
                                        .plusDays(1)
                                        .atStartOfDay(zone(input.timezone()))
                                        .toInstant())) {
            throw new InputException(
                    "VALIDATION_FAILED",
                    "knowledgeCutoff",
                    "Historical knowledge must end at its origin cutoff");
        }
    }

    public void validateDays(
            List<DayFact> days,
            List<Payment> payments,
            LocalDate from,
            LocalDate to,
            boolean completeRequired) {
        unique(days, DayFact::date, "dailyHistory");
        unique(payments, Payment::paymentId, "payments");
        for (var day : days) {
            require(
                    !day.date().isBefore(from) && !day.date().isAfter(to),
                    "dailyHistory",
                    "Day outside requested interval");
            if (day.status() == DailyStatus.MISSING) {
                require(
                        !completeRequired
                                && day.inflow() == null
                                && day.outflow() == null
                                && day.balanceEod() == null,
                        "dailyHistory",
                        "Missing day must have unknown amounts");
            } else {
                require(
                        day.inflow() != null
                                && day.outflow() != null
                                && day.inflow().signum() >= 0
                                && day.outflow().signum() >= 0,
                        "dailyHistory",
                        "Complete day requires non-negative flows");
                require(
                        day.status() != DailyStatus.ZERO_CONFIRMED
                                || day.inflow().signum() == 0
                                        && day.outflow().signum() == 0
                                        && day.paymentCount() == 0,
                        "dailyHistory",
                        "Confirmed zero day contains payments");
            }
        }
        long expected = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        require(days.size() == expected, "dailyHistory", "Every calendar day must be explicit");
        Map<LocalDate, java.math.BigDecimal[]> totals = new HashMap<>();
        Map<LocalDate, Integer> counts = new HashMap<>();
        for (var payment : payments) {
            require(
                    !payment.date().isBefore(from)
                            && !payment.date().isAfter(to)
                            && payment.amount().signum() > 0,
                    "payments",
                    "Payment outside cutoff or non-positive amount");
            var sum =
                    totals.computeIfAbsent(
                            payment.date(),
                            ignored -> new java.math.BigDecimal[] {Money.ZERO, Money.ZERO});
            int direction = payment.direction() == Direction.IN ? 0 : 1;
            sum[direction] = sum[direction].add(payment.amount());
            counts.merge(payment.date(), 1, Integer::sum);
        }
        for (var day : days) {
            var sum =
                    totals.getOrDefault(
                            day.date(), new java.math.BigDecimal[] {Money.ZERO, Money.ZERO});
            require(
                    day.paymentCount() == counts.getOrDefault(day.date(), 0),
                    "dailyHistory.paymentCount",
                    "Payment count disagrees with facts");
            if (day.complete()) {
                require(
                        day.inflow().compareTo(sum[0]) == 0 && day.outflow().compareTo(sum[1]) == 0,
                        "dailyHistory",
                        "Daily flows disagree with payment facts");
            }
        }
    }

    private <T, K> void unique(List<T> items, Function<T, K> identity, String field) {
        Set<K> identities = new HashSet<>();
        require(
                items.stream().allMatch(item -> identities.add(identity.apply(item))),
                field,
                "Duplicate identity");
    }

    private void require(boolean condition, String field, String message) {
        if (!condition) {
            throw new InputException("VALIDATION_FAILED", field, message);
        }
    }

    private static boolean manualPattern(RecurringPattern pattern) {
        return "MANUAL_V1".equals(pattern.detectorVersion())
                && pattern.observations() == 0
                && pattern.firstSeen() == null
                && pattern.lastSeen() == null;
    }

    private static boolean observedPattern(RecurringPattern pattern, LocalDate asOf) {
        return !"MANUAL_V1".equals(pattern.detectorVersion())
                && pattern.observations() >= 3
                && pattern.firstSeen() != null
                && pattern.lastSeen() != null
                && !pattern.firstSeen().isAfter(pattern.lastSeen())
                && !pattern.lastSeen().isAfter(asOf);
    }
}
