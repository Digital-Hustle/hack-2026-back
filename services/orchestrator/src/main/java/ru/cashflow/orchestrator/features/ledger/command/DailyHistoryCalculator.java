package ru.cashflow.orchestrator.features.ledger.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.DailyObservation;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.DayStatus;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.StartBalance;

/**
 * Pure reconstruction of daily history from facts, anchors and completeness that the caller has already
 * filtered to one account and one knowledge cutoff. Used both for the live projection and for historical
 * snapshots, so a past cutoff never reads today's projection (handoff §2).
 */
public final class DailyHistoryCalculator {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private DailyHistoryCalculator() {
    }

    /** Exactly one observation per calendar day in [from, to], without gaps or duplicates. */
    public static List<DailyObservation> calculate(LocalDate from, LocalDate to, List<Payment> payments,
                                                   List<BalanceAnchor> anchors, List<CompletenessRange> ranges) {
        if (from.isAfter(to)) {
            return List.of();
        }
        Map<LocalDate, BigDecimal[]> flows = new HashMap<>();
        Map<LocalDate, Integer> counts = new HashMap<>();
        for (Payment payment : payments) {
            var sums = flows.computeIfAbsent(payment.operationDate(), ignored -> new BigDecimal[] {ZERO, ZERO});
            int slot = payment.direction() == Direction.IN ? 0 : 1;
            sums[slot] = sums[slot].add(payment.amount());
            counts.merge(payment.operationDate(), 1, Integer::sum);
        }
        Map<LocalDate, BigDecimal> eods = resolveAnchors(anchors);
        List<CompletenessRange> ordered = ranges.stream()
                .sorted(Comparator.comparing(CompletenessRange::knownAt).thenComparing(r -> r.id().toString()))
                .toList();
        List<DailyObservation> days = new ArrayList<>();
        DailyObservation previous = null;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            int count = counts.getOrDefault(date, 0);
            DailyObservation day;
            if (complete(date, ordered)) {
                BigDecimal[] sums = flows.getOrDefault(date, new BigDecimal[] {ZERO, ZERO});
                BigDecimal eod = eods.get(date);
                boolean mismatch = previous != null && previous.status() != DayStatus.MISSING
                        && previous.balanceEod() != null && eod != null
                        && previous.balanceEod().add(sums[0]).subtract(sums[1]).subtract(eod).abs()
                        .compareTo(TOLERANCE) >= 0;
                day = new DailyObservation(date, sums[0], sums[1], eod,
                        count > 0 ? DayStatus.OBSERVED : DayStatus.ZERO_CONFIRMED, count, mismatch);
            } else {
                day = new DailyObservation(date, null, null, null, DayStatus.MISSING, count, false);
            }
            days.add(day);
            previous = day;
        }
        return List.copyOf(days);
    }

    /**
     * B(T): the latest anchor on or before T, rolled forward only through complete days. An unknown day between
     * the anchor and T makes the balance unavailable instead of silently treating missing flows as zero.
     */
    public static Optional<StartBalance> startBalance(LocalDate asOf, List<BalanceAnchor> anchors,
                                                      List<DailyObservation> days) {
        Map<LocalDate, BigDecimal> eods = resolveAnchors(anchors);
        Optional<LocalDate> anchorDate = eods.keySet().stream().filter(d -> !d.isAfter(asOf))
                .max(Comparator.naturalOrder());
        if (anchorDate.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal balance = eods.get(anchorDate.get());
        Map<LocalDate, DailyObservation> byDate = new HashMap<>();
        days.forEach(day -> byDate.put(day.date(), day));
        for (LocalDate date = anchorDate.get().plusDays(1); !date.isAfter(asOf); date = date.plusDays(1)) {
            DailyObservation day = byDate.get(date);
            if (day == null || day.status() == DayStatus.MISSING) {
                return Optional.empty();
            }
            balance = balance.add(day.inflow()).subtract(day.outflow());
        }
        return Optional.of(new StartBalance(balance, anchorDate.get()));
    }

    /** For each date the most recently known anchor wins; a manual anchor supersedes the statement value. */
    public static Map<LocalDate, BigDecimal> resolveAnchors(List<BalanceAnchor> anchors) {
        Map<LocalDate, BalanceAnchor> latest = new HashMap<>();
        Comparator<BalanceAnchor> order = Comparator.comparing(BalanceAnchor::knownAt)
                .thenComparing(a -> a.id().toString());
        for (BalanceAnchor anchor : anchors) {
            latest.merge(anchor.date(), anchor, (a, b) -> order.compare(a, b) >= 0 ? a : b);
        }
        Map<LocalDate, BigDecimal> result = new HashMap<>();
        latest.forEach((date, anchor) -> result.put(date, anchor.balance()));
        return result;
    }

    private static boolean complete(LocalDate date, List<CompletenessRange> ordered) {
        Completeness state = Completeness.UNKNOWN;
        for (CompletenessRange range : ordered) {
            if (!date.isBefore(range.fromDate()) && !date.isAfter(range.toDate())) {
                state = range.completeness();
            }
        }
        return state == Completeness.CONFIRMED_COMPLETE;
    }
}
