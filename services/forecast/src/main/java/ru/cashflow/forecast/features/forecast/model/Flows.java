package ru.cashflow.forecast.features.forecast.model;

import java.math.BigDecimal;
import ru.cashflow.forecast.features.shared.model.Money;

public record Flows(BigDecimal inflow, BigDecimal outflow) {
    public static final Flows ZERO = new Flows(Money.ZERO, Money.ZERO);
}
