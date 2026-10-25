package ru.cashflow.forecast.features.backtest.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One forecast day of one origin next to what actually happened; the data behind the forecast-vs-actual chart. */
public record DayComparison(LocalDate date, int horizonDay, BigDecimal forecastBalance, BigDecimal actualBalance,
        BigDecimal forecastInflow, BigDecimal forecastOutflow, BigDecimal actualInflow, BigDecimal actualOutflow,
        BigDecimal balanceP10, BigDecimal balanceP90, Double pBelowZero, boolean predictedBreach,
        boolean actualBreach) { }
