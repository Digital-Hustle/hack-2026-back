package ru.cashflow.forecast.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BacktestOriginRs(LocalDate asOfDate, String status, String reason, BigDecimal maeBalance, int evaluatedDays) { }
