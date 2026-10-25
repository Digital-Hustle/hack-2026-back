package ru.cashflow.orchestrator.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import ru.cashflow.orchestrator.features.analytics.model.AnalyticsTypes.Entry;
import ru.cashflow.orchestrator.features.analytics.model.AnalyticsTypes.Report;
import ru.cashflow.orchestrator.features.analytics.model.AnalyticsTypes.Total;

/** Read-only analytics: exact decimal strings and independent IN/OUT totals. */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public record TotalRs(String direction, String amount, long paymentCount) {
        static TotalRs of(Total total) {
            return new TotalRs(total.direction().name(), money(total.amount()), total.paymentCount());
        }
    }

    public record CategoryRs(String direction, String categoryCode, String amount, long paymentCount,
                             String sharePercent) {
        static CategoryRs of(Entry entry) {
            return new CategoryRs(entry.direction().name(), entry.key(), money(entry.amount()),
                    entry.paymentCount(), percent(entry.sharePercent()));
        }
    }

    public record CounterpartyRs(String direction, String counterpartyKey, String counterpartyInn,
                                 String counterpartyName, String amount, long paymentCount, String sharePercent) {
        static CounterpartyRs of(Entry entry) {
            return new CounterpartyRs(entry.direction().name(), entry.key(), entry.inn(), entry.name(),
                    money(entry.amount()), entry.paymentCount(), percent(entry.sharePercent()));
        }
    }

    public record CategoriesRs(UUID companyId, UUID accountId, LocalDate fromDate, LocalDate toDate,
                               String direction, List<TotalRs> totals, List<CategoryRs> items) {
        public static CategoriesRs of(Report report) {
            return new CategoriesRs(report.companyId(), report.accountId(), report.fromDate(), report.toDate(),
                    AnalyticsDtos.direction(report), report.totals().stream().map(TotalRs::of).toList(),
                    report.items().stream().map(CategoryRs::of).toList());
        }
    }

    public record CounterpartiesRs(UUID companyId, UUID accountId, LocalDate fromDate, LocalDate toDate,
                                   String direction, List<TotalRs> totals, List<CounterpartyRs> items) {
        public static CounterpartiesRs of(Report report) {
            return new CounterpartiesRs(report.companyId(), report.accountId(), report.fromDate(), report.toDate(),
                    AnalyticsDtos.direction(report), report.totals().stream().map(TotalRs::of).toList(),
                    report.items().stream().map(CounterpartyRs::of).toList());
        }
    }

    private static String direction(Report report) {
        return report.direction() == null ? null : report.direction().name();
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2).toPlainString();
    }

    private static String percent(BigDecimal value) {
        return value.setScale(2).toPlainString();
    }
}
