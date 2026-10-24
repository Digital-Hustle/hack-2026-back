package ru.cashflow.forecast.features.risk.model;

import java.math.BigDecimal;

public record RiskDay(BigDecimal balanceP10, BigDecimal balanceP50, BigDecimal balanceP90,
        Double pBelowZero, Double pBelowBuffer) { }
