package ru.cashflow.forecast.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BacktestOriginRs(LocalDate asOfDate, String status, String reason, BigDecimal maeBalance, int evaluatedDays,
        List<DayComparisonRs> days) { }
