package ru.cashflow.orchestrator.features.analytics.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.analytics.model.AnalyticsTypes.Aggregate;
import ru.cashflow.orchestrator.features.analytics.model.AnalyticsTypes.Entry;
import ru.cashflow.orchestrator.features.analytics.model.AnalyticsTypes.Report;
import ru.cashflow.orchestrator.features.analytics.model.AnalyticsTypes.Total;
import ru.cashflow.orchestrator.features.analytics.repository.AnalyticsRepository;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;

/** Current-state analytics, not an as-of historical backtest. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AnalyticsService {

    private static final int MAX_DAYS = 366;
    private static final int PERCENT_SCALE = 2;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final CompanyAccess access;
    private final CompanyRepository companies;
    private final AnalyticsRepository analytics;

    public AnalyticsService(CompanyAccess access, CompanyRepository companies, AnalyticsRepository analytics) {
        this.access = access;
        this.companies = companies;
        this.analytics = analytics;
    }

    public Report categories(UUID userId, UUID companyId, UUID accountId, LocalDate fromDate, LocalDate toDate,
                             Direction direction) {
        validate(fromDate, toDate);
        var scope = access.requireRead(userId, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        return report(companyId, accountId, fromDate, toDate, direction,
                analytics.byCategory(scope, accountId, fromDate, toDate, direction));
    }

    public Report counterparties(UUID userId, UUID companyId, UUID accountId, LocalDate fromDate, LocalDate toDate,
                                 Direction direction) {
        validate(fromDate, toDate);
        var scope = access.requireRead(userId, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        return report(companyId, accountId, fromDate, toDate, direction,
                analytics.byCounterparty(scope, accountId, fromDate, toDate, direction));
    }

    private static Report report(UUID companyId, UUID accountId, LocalDate from, LocalDate to, Direction direction,
                                 List<Aggregate> aggregates) {
        Map<Direction, BigDecimal> amounts = new EnumMap<>(Direction.class);
        Map<Direction, Long> counts = new EnumMap<>(Direction.class);
        List<Direction> directions = direction == null ? List.of(Direction.IN, Direction.OUT) : List.of(direction);
        for (Direction selected : directions) {
            amounts.put(selected, ZERO);
            counts.put(selected, 0L);
        }
        for (Aggregate aggregate : aggregates) {
            amounts.merge(aggregate.direction(), aggregate.amount(), BigDecimal::add);
            counts.merge(aggregate.direction(), aggregate.paymentCount(), Long::sum);
        }

        List<Total> totals = new ArrayList<>();
        for (Direction selected : directions) {
            totals.add(new Total(selected, amounts.get(selected), counts.get(selected)));
        }
        List<Entry> entries = aggregates.stream().map(aggregate -> new Entry(aggregate.direction(),
                aggregate.key(), aggregate.inn(), aggregate.name(), aggregate.amount(), aggregate.paymentCount(),
                share(aggregate.amount(), amounts.get(aggregate.direction())))).toList();
        return new Report(companyId, accountId, from, to, direction, totals, entries);
    }

    private static BigDecimal share(BigDecimal amount, BigDecimal total) {
        if (total.signum() == 0) {
            return ZERO;
        }
        return amount.multiply(ONE_HUNDRED).divide(total, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private static void validate(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)
                || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw ApiException.unprocessable("INVALID_RANGE", "fromDate и toDate: период от 1 до 366 дней");
        }
    }
}
