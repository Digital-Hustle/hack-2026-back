package ru.cashflow.forecast.features.backtest.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record OriginResult(LocalDate asOfDate, String status, String reason, BigDecimal maeBalance, int evaluatedDays,
        List<DayComparison> days) {
    public OriginResult {
        days = List.copyOf(days);
    }

    public OriginResult(LocalDate asOfDate, String status, String reason, BigDecimal maeBalance, int evaluatedDays) {
        this(asOfDate, status, reason, maeBalance, evaluatedDays, List.of());
    }
}
