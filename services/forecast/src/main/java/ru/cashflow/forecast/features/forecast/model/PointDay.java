package ru.cashflow.forecast.features.forecast.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PointDay(LocalDate date, BigDecimal plannedIn, BigDecimal plannedOut, BigDecimal recurringIn,
        BigDecimal recurringOut, BigDecimal residualIn, BigDecimal residualOut, BigDecimal inflow,
        BigDecimal outflow, BigDecimal balance) { }
