package ru.cashflow.forecast.api.error;

public record FieldErrorRs(String field, String code, String message) { }
