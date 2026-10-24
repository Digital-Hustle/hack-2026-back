package ru.cashflow.orchestrator.features.paymentcalendar.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.paymentcalendar.command.PaymentCalendarBuilder;
import ru.cashflow.orchestrator.features.paymentcalendar.model.PaymentCalendar;
import ru.cashflow.orchestrator.features.plans.repository.PlanRepository;
import ru.cashflow.orchestrator.features.recurring.repository.RecurringRepository;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PaymentCalendarService {

    private static final long MAX_DAYS = 366;
    private final CompanyAccess access;
    private final CompanyRepository companies;
    private final PlanRepository plans;
    private final RecurringRepository recurring;
    private final Clock clock;

    public PaymentCalendarService(CompanyAccess access, CompanyRepository companies, PlanRepository plans,
                                   RecurringRepository recurring, Clock clock) {
        this.access = access;
        this.companies = companies;
        this.plans = plans;
        this.recurring = recurring;
        this.clock = clock;
    }

    public PaymentCalendar get(UUID user, UUID companyId, UUID accountId, LocalDate from, LocalDate to) {
        var scope = access.requireRead(user, companyId);
        var account = companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        var company = companies.findCompany(scope).orElseThrow(ApiException::notFound);
        if (from == null || to == null || from.isAfter(to) || from.getYear() < 1 || to.getYear() > 9999
                || ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw ApiException.unprocessable("INVALID_RANGE", "Период календаря: от 1 до 366 дней, годы 0001–9999");
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(company.timezone())));
        // Include nominal dates just before the range, so shifted replacements do not duplicate a recurring item.
        var days = PaymentCalendarBuilder.days(from, to, today, plans.list(scope, accountId, from.minusDays(2), to),
                recurring.list(scope, accountId));
        var overdue = plans.overdueOutflowsBefore(scope, accountId, from.isBefore(today) ? from : today).stream()
                .map(plan -> PaymentCalendarBuilder.planItem(plan, today)).toList();
        var totals = PaymentCalendarBuilder.totals(days.stream().flatMap(day -> day.items().stream()).toList());
        boolean hasRecurring = days.stream().flatMap(day -> day.items().stream())
                .anyMatch(item -> item.kind() == PaymentCalendar.Kind.RECURRING);
        return new PaymentCalendar(companyId, accountId, account.currency(), company.timezone(), today,
                company.dataVersion(), from, to, totals, days, PaymentCalendarBuilder.totals(overdue).remainingAmount(),
                overdue, hasRecurring ? List.of("SIMPLIFIED_BUSINESS_CALENDAR", "RECURRING_SETTLEMENT_UNKNOWN") : List.of());
    }
}
