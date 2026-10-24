package ru.cashflow.forecast.features.shared.model;

import java.time.LocalDate;

public record Warning(String code, String message, LocalDate date) { }
