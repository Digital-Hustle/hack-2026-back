package ru.cashflow.preprocessor.features.statements.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import ru.cashflow.preprocessor.features.statements.model.DaySummary;
import ru.cashflow.preprocessor.features.statements.model.Direction;
import ru.cashflow.preprocessor.features.statements.model.ParsedOperation;

final class DailyBalanceBuilder {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    List<DaySummary> build(List<ParsedOperation> operations, IssueCollector issues) {
        Map<Key, Day> days = new TreeMap<>(Comparator.comparing(Key::company).thenComparing(Key::date));
        for (ParsedOperation operation : operations) {
            var day = days.computeIfAbsent(new Key(operation.companyId(), operation.operationDate()),
                    ignored -> new Day(operation));
            if (operation.direction() == Direction.IN) {
                day.inflow = day.inflow.add(operation.amount());
            } else {
                day.outflow = day.outflow.add(operation.amount());
            }
            if (operation.balanceEod() != null) {
                if (day.balance == null) {
                    day.balance = operation.balanceEod();
                } else if (day.balance.compareTo(operation.balanceEod()) != 0) {
                    day.conflict = true;
                }
            }
        }
        List<DaySummary> summaries = new ArrayList<>();
        Map<String, DaySummary> previous = new HashMap<>();
        for (var entry : days.entrySet()) {
            Key key = entry.getKey();
            Day day = entry.getValue();
            String status = day.conflict ? "CONFLICT" : day.balance == null ? "MISSING" : "OBSERVED";
            BigDecimal anchor = day.conflict ? null : day.balance;
            DaySummary prior = previous.get(key.company());
            Boolean reconciles = null;
            if (day.conflict) {
                issue(issues, day, "BALANCE_CONFLICT", "В одном дне указаны разные остатки; дневной якорь не выбран");
            }
            if (prior != null) {
                if (ChronoUnit.DAYS.between(prior.date(), key.date()) > 7) {
                    issue(issues, day, "HISTORY_GAP", "Между наблюдениями более 7 дней; полнота периода не подтверждена");
                }
                if (anchor != null && prior.balanceEod() != null) {
                    BigDecimal expected = prior.balanceEod().add(day.inflow).subtract(day.outflow);
                    reconciles = expected.subtract(anchor).abs().compareTo(TOLERANCE) <= 0;
                    if (!reconciles) {
                        issue(issues, day, "BALANCE_MISMATCH", "Остаток не согласован с предыдущим наблюдением и потоками; проверьте полноту данных");
                    }
                }
            }
            var summary = new DaySummary(key.company(), key.date(), day.inflow, day.outflow, anchor, status, reconciles);
            summaries.add(summary);
            previous.put(key.company(), summary);
        }
        return List.copyOf(summaries);
    }

    private void issue(IssueCollector issues, Day day, String code, String message) {
        issues.add(code, "WARNING", "STATEMENTS", day.first.sourceRecord(), day.first.sourceLine(), "balance_eod", message);
    }

    private record Key(String company, LocalDate date) {}

    private static final class Day {
        private final ParsedOperation first;
        private BigDecimal inflow = ZERO;
        private BigDecimal outflow = ZERO;
        private BigDecimal balance;
        private boolean conflict;

        Day(ParsedOperation first) {
            this.first = first;
        }
    }
}
