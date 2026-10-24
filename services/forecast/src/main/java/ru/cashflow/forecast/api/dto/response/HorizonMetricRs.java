package ru.cashflow.forecast.api.dto.response;

import java.math.BigDecimal;

public record HorizonMetricRs(int horizonDay, BigDecimal maeBalance, BigDecimal rmseBalance, int evaluatedCount) { }
