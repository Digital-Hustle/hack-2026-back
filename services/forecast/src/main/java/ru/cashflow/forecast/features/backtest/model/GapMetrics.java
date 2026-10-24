package ru.cashflow.forecast.features.backtest.model;

import java.math.BigDecimal;

public record GapMetrics(int truePositive, int falsePositive, int falseNegative, Double precision, Double recall,
        Double medianLeadDays, Double dateMaeDays, BigDecimal shortfallMae) { }
