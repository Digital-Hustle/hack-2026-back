package ru.cashflow.orchestrator.features.planfact.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;

/** Read model for plan-vs-fact. Plans are grouped by dueDate, facts by payment operationDate. */
public final class PlanFactTypes {

    private PlanFactTypes() {
    }

    public enum GroupBy { DAY, WEEK }

    public record DailyPlan(LocalDate date, Direction direction, BigDecimal planned, BigDecimal settled,
                            BigDecimal remaining) {
    }

    public record DailyActual(LocalDate date, Direction direction, BigDecimal amount) {
    }

    public record DailyUnallocated(LocalDate date, Direction direction, BigDecimal amount) {
    }

    public record OverduePlan(UUID id, String title, Direction direction, LocalDate dueDate, BigDecimal amount,
                              BigDecimal settled, BigDecimal remaining) {
    }

    public record UnplannedFact(UUID id, LocalDate date, Direction direction, BigDecimal amount,
                                BigDecimal unallocated, String counterpartyName, String categoryCode) {
    }

    public record Totals(BigDecimal plannedIn, BigDecimal plannedOut, BigDecimal actualIn, BigDecimal actualOut,
                         BigDecimal varianceIn, BigDecimal varianceOut, BigDecimal settledIn, BigDecimal settledOut,
                         BigDecimal remainingIn, BigDecimal remainingOut, BigDecimal unplannedIn,
                         BigDecimal unplannedOut, BigDecimal overdueRemainingIn, BigDecimal overdueRemainingOut) {
    }

    public record Period(LocalDate start, LocalDate end, BigDecimal plannedIn, BigDecimal plannedOut,
                         BigDecimal actualIn, BigDecimal actualOut, BigDecimal varianceIn, BigDecimal varianceOut,
                         BigDecimal unplannedIn, BigDecimal unplannedOut, List<OverduePlan> overduePlans,
                         List<UnplannedFact> unplannedFacts) {
    }

    public record PlanFact(UUID companyId, UUID accountId, LocalDate fromDate, LocalDate toDate, GroupBy groupBy,
                           LocalDate today, Totals totals, List<Period> periods) {
    }
}
