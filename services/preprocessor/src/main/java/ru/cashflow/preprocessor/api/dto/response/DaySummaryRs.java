package ru.cashflow.preprocessor.api.dto.response;

import java.time.LocalDate;

public record DaySummaryRs(String companyId, LocalDate date, String inflow, String outflow, String balanceEod,
                           String balanceStatus, Boolean reconcilesWithPrevious) {}
