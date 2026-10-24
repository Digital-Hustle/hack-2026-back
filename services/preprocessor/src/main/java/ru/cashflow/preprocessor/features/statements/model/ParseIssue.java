package ru.cashflow.preprocessor.features.statements.model;

public record ParseIssue(String code, String severity, String source, long sourceRecord, long sourceLine,
                         String field, String message) {}
