package ru.cashflow.orchestrator.features.analytics.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;

/** Account-scoped cash movements; percentages always use the total of the same direction. */
public final class AnalyticsTypes {

    private AnalyticsTypes() {
    }

    public record Aggregate(Direction direction, String key, String inn, String name,
                            BigDecimal amount, long paymentCount) {
    }

    public record Total(Direction direction, BigDecimal amount, long paymentCount) {
    }

    public record Entry(Direction direction, String key, String inn, String name,
                        BigDecimal amount, long paymentCount, BigDecimal sharePercent) {
    }

    public record Report(UUID companyId, UUID accountId, LocalDate fromDate, LocalDate toDate,
                         Direction direction, List<Total> totals, List<Entry> items) {
    }
}
