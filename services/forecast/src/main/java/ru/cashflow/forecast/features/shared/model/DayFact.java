package ru.cashflow.forecast.features.shared.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DayFact(LocalDate date, BigDecimal inflow, BigDecimal outflow, BigDecimal balanceEod,
        DailyStatus status, int paymentCount, boolean balanceMismatch) {
    public boolean complete() {
        return status != DailyStatus.MISSING;
    }
}
