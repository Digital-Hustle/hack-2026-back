package ru.cashflow.forecast.features.backtest.model;

import java.math.BigDecimal;

/**
 * Binary "balance below threshold" classification. Every ratio is null when its denominator is zero (undefined,
 * not 100%). majorityBaselineAccuracy is the accuracy of always predicting the more frequent class: accuracy alone
 * is only informative when it exceeds it.
 */
public record ConfusionMatrix(BigDecimal threshold, int truePositive, int falsePositive, int falseNegative,
        int trueNegative, int total, Double accuracy, Double precision, Double recall, Double specificity, Double f1,
        Double balancedAccuracy, Double prevalence, Double majorityBaselineAccuracy) {

    public static ConfusionMatrix of(BigDecimal threshold, int tp, int fp, int fn, int tn) {
        int total = tp + fp + fn + tn;
        Double recall = ratio(tp, tp + fn);
        Double specificity = ratio(tn, tn + fp);
        return new ConfusionMatrix(threshold, tp, fp, fn, tn, total, ratio(tp + tn, total), ratio(tp, tp + fp), recall,
                specificity, ratio(2 * tp, 2 * tp + fp + fn),
                recall == null || specificity == null ? null : (recall + specificity) / 2.0,
                ratio(tp + fn, total), total == 0 ? null : (double) Math.max(tp + fn, fp + tn) / total);
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }
}
