package ru.cashflow.forecast.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ForecastDayRs(LocalDate date, BigDecimal plannedIn, BigDecimal plannedOut, BigDecimal recurringIn,
        BigDecimal recurringOut, BigDecimal residualIn, BigDecimal residualOut, BigDecimal inflow,
        BigDecimal outflow, BigDecimal balance, BigDecimal balanceP10, BigDecimal balanceP50,
        BigDecimal balanceP90, Double pBelowZero, Double pBelowBuffer) { }
