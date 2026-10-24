package ru.cashflow.forecast.features.backtest.model;

import java.math.BigDecimal;

public record HorizonMetric(int horizonDay, BigDecimal maeBalance, BigDecimal rmseBalance, int evaluatedCount) { }
