package ru.cashflow.preprocessor.config;

public record ParseLimits(long statementBytes, long companyBytes, int maxRows, int maxFieldLength,
                          int maxIssues, int timeoutSeconds) {

    public ParseLimits {
        if (statementBytes <= 0 || companyBytes <= 0 || maxRows <= 0 || maxFieldLength <= 0 || maxIssues <= 0
                || timeoutSeconds <= 0 || maxFieldLength > 1_000_000) {
            throw new IllegalArgumentException("Лимиты размера, строк, полей, ошибок и времени должны быть положительными");
        }
    }

    public static ParseLimits defaults() {
        return new ParseLimits(100L * 1024 * 1024, 2L * 1024 * 1024, 250_000, 4096, 1000, 120);
    }
}
