package ru.cashflow.preprocessor.features.statements.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DaySummary(String companyId, LocalDate date, BigDecimal inflow, BigDecimal outflow,
                         BigDecimal balanceEod, String balanceStatus, Boolean reconcilesWithPrevious) {}
