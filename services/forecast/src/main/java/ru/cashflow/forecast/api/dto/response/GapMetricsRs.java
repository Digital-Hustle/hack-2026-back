package ru.cashflow.forecast.api.dto.response;

import java.math.BigDecimal;

public record GapMetricsRs(int truePositive, int falsePositive, int falseNegative, Double precision, Double recall,
        Double medianLeadDays, Double dateMaeDays, BigDecimal shortfallMae, Double f1) { }
