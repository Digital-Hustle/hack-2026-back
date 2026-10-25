package ru.cashflow.forecast.features.backtest.model;

import java.math.BigDecimal;

/**
 * Accuracy of one model on the same origins/days. skillScoreVsNaive = 1 - MAE / MAE(naive), so 0 means no better than
 * "the balance stays as at the cutoff" and negative means worse. WAPE = sum|error| / sum|actual balance| (null when the
 * actual balances sum to zero). Flow MAEs are null for models that do not forecast flows.
 */
public record ModelScore(String model, String description, int evaluatedDays, BigDecimal maeBalance,
        BigDecimal mseBalance, BigDecimal rmseBalance, Double wapeBalance, Double skillScoreVsNaive,
        BigDecimal inflowMae, BigDecimal outflowMae, ConfusionMatrix dailyMatrix) { }
