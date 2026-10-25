package ru.cashflow.forecast.api.dto.response;

import java.math.BigDecimal;

public record ModelScoreRs(String model, String description, int evaluatedDays, BigDecimal maeBalance,
        BigDecimal mseBalance, BigDecimal rmseBalance, Double wapeBalance, Double skillScoreVsNaive,
        BigDecimal inflowMae, BigDecimal outflowMae, ConfusionMatrixRs dailyMatrix) { }
