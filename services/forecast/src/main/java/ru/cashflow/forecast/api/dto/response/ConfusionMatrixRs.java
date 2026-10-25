package ru.cashflow.forecast.api.dto.response;

import java.math.BigDecimal;

public record ConfusionMatrixRs(BigDecimal threshold, int truePositive, int falsePositive, int falseNegative,
        int trueNegative, int total, Double accuracy, Double precision, Double recall, Double specificity, Double f1,
        Double balancedAccuracy, Double prevalence, Double majorityBaselineAccuracy) { }
