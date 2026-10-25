package ru.cashflow.forecast.features.backtest.model;

import java.math.BigDecimal;

/** Episode-level matching: a predicted and an actual gap episode match one-to-one by maximal day overlap. */
public record GapMetrics(int truePositive, int falsePositive, int falseNegative, Double precision, Double recall,
        Double medianLeadDays, Double dateMaeDays, BigDecimal shortfallMae, Double f1) {

    public GapMetrics(int truePositive, int falsePositive, int falseNegative, Double precision, Double recall,
            Double medianLeadDays, Double dateMaeDays, BigDecimal shortfallMae) {
        this(truePositive, falsePositive, falseNegative, precision, recall, medianLeadDays, dateMaeDays, shortfallMae,
                f1(truePositive, falsePositive, falseNegative));
    }

    /** F1 = 2TP / (2TP + FP + FN); undefined (null) when there are neither predicted nor actual episodes. */
    public static Double f1(int truePositive, int falsePositive, int falseNegative) {
        int denominator = 2 * truePositive + falsePositive + falseNegative;
        return denominator == 0 ? null : Double.valueOf(2.0 * truePositive / denominator);
    }
}
