package ru.cashflow.forecast.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DayComparisonRs(LocalDate date, int horizonDay, BigDecimal forecastBalance, BigDecimal actualBalance,
        BigDecimal forecastInflow, BigDecimal forecastOutflow, BigDecimal actualInflow, BigDecimal actualOutflow,
        BigDecimal balanceP10, BigDecimal balanceP90, Double pBelowZero, boolean predictedBreach,
        boolean actualBreach) { }
