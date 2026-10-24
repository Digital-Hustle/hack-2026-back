package ru.cashflow.orchestrator.features.planfact.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.Company;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.DailyActual;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.DailyPlan;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.DailyUnallocated;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.GroupBy;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.OverduePlan;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.Period;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.PlanFact;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.Totals;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.UnplannedFact;
import ru.cashflow.orchestrator.features.planfact.repository.PlanFactRepository;

/** Plan and fact stay separate: a plan is not marked paid by being in the same period, and a fact is not split by plan. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PlanFactService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final long MAX_DAYS = 366;

    private final CompanyAccess access;
    private final CompanyRepository companies;
    private final PlanFactRepository planFact;
    private final Clock clock;

    public PlanFactService(CompanyAccess access, CompanyRepository companies, PlanFactRepository planFact, Clock clock) {
        this.access = access;
        this.companies = companies;
        this.planFact = planFact;
        this.clock = clock;
    }

    public PlanFact get(UUID user, UUID companyId, UUID accountId, LocalDate fromDate, LocalDate toDate,
                        GroupBy groupBy) {
        CompanyScope scope = access.requireRead(user, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        validate(fromDate, toDate, groupBy);
        Company company = companies.findCompany(scope).orElseThrow(ApiException::notFound);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(company.timezone())));

        List<DailyPlan> plans = planFact.planned(scope, accountId, fromDate, toDate);
        List<DailyActual> actuals = planFact.actual(scope, accountId, fromDate, toDate);
        List<DailyUnallocated> unallocated = planFact.unallocated(scope, accountId, fromDate, toDate);
        List<OverduePlan> overdue = planFact.overduePlans(scope, accountId, fromDate, toDate, today);
        List<UnplannedFact> unplanned = planFact.unplannedFacts(scope, accountId, fromDate, toDate);

        Map<LocalDate, PeriodBuilder> periods = periods(fromDate, toDate, groupBy);
        for (DailyPlan plan : plans) {
            bucket(periods, plan.date(), groupBy).addPlan(plan);
        }
        for (DailyActual actual : actuals) {
            bucket(periods, actual.date(), groupBy).addActual(actual);
        }
        for (DailyUnallocated row : unallocated) {
            bucket(periods, row.date(), groupBy).addUnallocated(row);
        }
        for (OverduePlan plan : overdue) {
            bucket(periods, plan.dueDate(), groupBy).overdue.add(plan);
        }
        for (UnplannedFact fact : unplanned) {
            bucket(periods, fact.date(), groupBy).unplanned.add(fact);
        }

        Totals totals = totals(plans, actuals, unallocated, overdue);
        return new PlanFact(companyId, accountId, fromDate, toDate, groupBy, today, totals,
                periods.values().stream().map(PeriodBuilder::toPeriod).toList());
    }

    private static Map<LocalDate, PeriodBuilder> periods(LocalDate from, LocalDate to, GroupBy groupBy) {
        Map<LocalDate, PeriodBuilder> periods = new LinkedHashMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            LocalDate key = groupBy == GroupBy.WEEK
                    ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : date;
            LocalDate start = groupBy == GroupBy.WEEK ? max(from, key) : date;
            LocalDate end = groupBy == GroupBy.WEEK ? min(to, key.plusDays(6)) : date;
            periods.computeIfAbsent(key, ignored -> new PeriodBuilder(start, end));
        }
        return periods;
    }

    private static PeriodBuilder bucket(Map<LocalDate, PeriodBuilder> periods, LocalDate date, GroupBy groupBy) {
        LocalDate key = groupBy == GroupBy.WEEK
                ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : date;
        PeriodBuilder period = periods.get(key);
        if (period == null) {
            throw new IllegalStateException("Plan-fact date escaped the requested range");
        }
        return period;
    }

    private static Totals totals(List<DailyPlan> plans, List<DailyActual> actuals, List<DailyUnallocated> unallocated,
                                 List<OverduePlan> overdue) {
        TotalsBuilder totals = new TotalsBuilder();
        plans.forEach(plan -> totals.addPlan(plan));
        actuals.forEach(actual -> totals.addActual(actual));
        unallocated.forEach(row -> totals.addUnallocated(row));
        overdue.forEach(totals::addOverdue);
        return totals.toTotals();
    }

    private static void validate(LocalDate from, LocalDate to, GroupBy groupBy) {
        if (from == null || to == null || groupBy == null) {
            throw ApiException.unprocessable("INVALID_RANGE", "fromDate, toDate и groupBy обязательны");
        }
        if (from.isAfter(to)) {
            throw ApiException.unprocessable("INVALID_RANGE", "fromDate не может быть позже toDate");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw ApiException.unprocessable("INVALID_RANGE", "Период план-факта не должен превышать 366 дней");
        }
    }

    private static LocalDate max(LocalDate left, LocalDate right) {
        return left.isAfter(right) ? left : right;
    }

    private static LocalDate min(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private static final class PeriodBuilder {
        private final LocalDate start;
        private final LocalDate end;
        private final List<OverduePlan> overdue = new ArrayList<>();
        private final List<UnplannedFact> unplanned = new ArrayList<>();
        private final Amounts amounts = new Amounts();

        private PeriodBuilder(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }

        private void addPlan(DailyPlan plan) {
            amounts.addPlan(plan);
        }

        private void addActual(DailyActual actual) {
            amounts.addActual(actual);
        }

        private void addUnallocated(DailyUnallocated row) {
            amounts.addUnallocated(row);
        }

        private Period toPeriod() {
            overdue.sort(java.util.Comparator.comparing(OverduePlan::dueDate).thenComparing(p -> p.id().toString()));
            unplanned.sort(java.util.Comparator.comparing(UnplannedFact::date).thenComparing(f -> f.id().toString()));
            return new Period(start, end, amounts.plannedIn, amounts.plannedOut, amounts.actualIn, amounts.actualOut,
                    amounts.varianceIn(), amounts.varianceOut(), amounts.unplannedIn, amounts.unplannedOut,
                    List.copyOf(overdue), List.copyOf(unplanned));
        }
    }

    private static final class TotalsBuilder {
        private final Amounts amounts = new Amounts();
        private BigDecimal overdueIn = ZERO;
        private BigDecimal overdueOut = ZERO;

        private void addPlan(DailyPlan plan) {
            amounts.addPlan(plan);
        }

        private void addActual(DailyActual actual) {
            amounts.addActual(actual);
        }

        private void addUnallocated(DailyUnallocated row) {
            amounts.addUnallocated(row);
        }

        private void addOverdue(OverduePlan plan) {
            if (plan.direction() == Direction.IN) {
                overdueIn = overdueIn.add(plan.remaining());
            } else {
                overdueOut = overdueOut.add(plan.remaining());
            }
        }

        private Totals toTotals() {
            return new Totals(amounts.plannedIn, amounts.plannedOut, amounts.actualIn, amounts.actualOut,
                    amounts.varianceIn(), amounts.varianceOut(), amounts.settledIn, amounts.settledOut,
                    amounts.remainingIn, amounts.remainingOut, amounts.unplannedIn, amounts.unplannedOut,
                    overdueIn, overdueOut);
        }
    }

    private static final class Amounts {
        private BigDecimal plannedIn = ZERO;
        private BigDecimal plannedOut = ZERO;
        private BigDecimal actualIn = ZERO;
        private BigDecimal actualOut = ZERO;
        private BigDecimal settledIn = ZERO;
        private BigDecimal settledOut = ZERO;
        private BigDecimal remainingIn = ZERO;
        private BigDecimal remainingOut = ZERO;
        private BigDecimal unplannedIn = ZERO;
        private BigDecimal unplannedOut = ZERO;

        private void addPlan(DailyPlan plan) {
            BigDecimal planned = plan.planned();
            BigDecimal settled = plan.settled();
            BigDecimal remaining = plan.remaining();
            if (plan.direction() == Direction.IN) {
                plannedIn = plannedIn.add(planned);
                settledIn = settledIn.add(settled);
                remainingIn = remainingIn.add(remaining);
            } else {
                plannedOut = plannedOut.add(planned);
                settledOut = settledOut.add(settled);
                remainingOut = remainingOut.add(remaining);
            }
        }

        private void addActual(DailyActual actual) {
            if (actual.direction() == Direction.IN) {
                actualIn = actualIn.add(actual.amount());
            } else {
                actualOut = actualOut.add(actual.amount());
            }
        }

        private void addUnallocated(DailyUnallocated row) {
            if (row.direction() == Direction.IN) {
                unplannedIn = unplannedIn.add(row.amount());
            } else {
                unplannedOut = unplannedOut.add(row.amount());
            }
        }

        private BigDecimal varianceIn() {
            return actualIn.subtract(plannedIn);
        }

        private BigDecimal varianceOut() {
            return actualOut.subtract(plannedOut);
        }
    }
}
