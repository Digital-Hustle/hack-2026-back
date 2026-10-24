package ru.cashflow.forecast.api.dto.response;

import java.time.LocalDate;

public record WarningRs(String code, String message, LocalDate date) { }
