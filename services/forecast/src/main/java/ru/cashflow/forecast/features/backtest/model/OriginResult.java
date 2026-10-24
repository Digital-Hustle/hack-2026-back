package ru.cashflow.forecast.features.backtest.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OriginResult(LocalDate asOfDate, String status, String reason, BigDecimal maeBalance, int evaluatedDays) { }
