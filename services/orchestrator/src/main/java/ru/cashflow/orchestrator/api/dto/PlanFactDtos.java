package ru.cashflow.orchestrator.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.OverduePlan;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.Period;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.PlanFact;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.Totals;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.UnplannedFact;

/** Money leaves the service as a decimal string with two digits; the response is chart-ready without recalculation. */
public final class PlanFactDtos {

    private PlanFactDtos() {
    }

    public record PlanFactRs(UUID companyId, UUID accountId, LocalDate fromDate, LocalDate toDate, String groupBy,
                             LocalDate today, TotalsRs totals, List<PeriodRs> periods) {
        public static PlanFactRs of(PlanFact planFact) {
            return new PlanFactRs(planFact.companyId(), planFact.accountId(), planFact.fromDate(), planFact.toDate(),
                    planFact.groupBy().name(), planFact.today(), TotalsRs.of(planFact.totals()),
                    planFact.periods().stream().map(PeriodRs::of).toList());
        }
    }

    public record TotalsRs(String plannedIn, String plannedOut, String actualIn, String actualOut,
                           String varianceIn, String varianceOut, String settledIn, String settledOut,
                           String remainingIn, String remainingOut, String unplannedIn, String unplannedOut,
                           String overdueRemainingIn, String overdueRemainingOut) {
        static TotalsRs of(Totals t) {
            return new TotalsRs(money(t.plannedIn()), money(t.plannedOut()), money(t.actualIn()), money(t.actualOut()),
                    money(t.varianceIn()), money(t.varianceOut()), money(t.settledIn()), money(t.settledOut()),
                    money(t.remainingIn()), money(t.remainingOut()), money(t.unplannedIn()), money(t.unplannedOut()),
                    money(t.overdueRemainingIn()), money(t.overdueRemainingOut()));
        }
    }

    public record PeriodRs(LocalDate periodStart, LocalDate periodEnd, String plannedIn, String plannedOut,
                           String actualIn, String actualOut, String varianceIn, String varianceOut,
                           String unplannedIn, String unplannedOut, List<OverduePlanRs> overduePlans,
                           List<UnplannedFactRs> unplannedFacts) {
        static PeriodRs of(Period p) {
            return new PeriodRs(p.start(), p.end(), money(p.plannedIn()), money(p.plannedOut()), money(p.actualIn()),
                    money(p.actualOut()), money(p.varianceIn()), money(p.varianceOut()), money(p.unplannedIn()),
                    money(p.unplannedOut()), p.overduePlans().stream().map(OverduePlanRs::of).toList(),
                    p.unplannedFacts().stream().map(UnplannedFactRs::of).toList());
        }
    }

    public record OverduePlanRs(UUID id, String title, String direction, LocalDate dueDate, String amount,
                                String settledAmount, String remainingAmount) {
        static OverduePlanRs of(OverduePlan p) {
            return new OverduePlanRs(p.id(), p.title(), p.direction().name(), p.dueDate(), money(p.amount()),
                    money(p.settled()), money(p.remaining()));
        }
    }

    public record UnplannedFactRs(UUID id, LocalDate operationDate, String direction, String amount,
                                  String unallocatedAmount, String counterpartyName, String categoryCode) {
        static UnplannedFactRs of(UnplannedFact f) {
            return new UnplannedFactRs(f.id(), f.date(), f.direction().name(), money(f.amount()),
                    money(f.unallocated()), f.counterpartyName(), f.categoryCode());
        }
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2).toPlainString();
    }
}
