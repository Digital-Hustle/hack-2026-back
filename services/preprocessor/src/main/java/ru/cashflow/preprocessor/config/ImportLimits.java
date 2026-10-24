package ru.cashflow.preprocessor.config;

/** Contract limits of the public synchronous upload: 20 MiB and 50 000 statement rows. */
public record ImportLimits(long maxFileBytes, int maxRows) {

    public ImportLimits {
        if (maxFileBytes <= 0 || maxRows <= 0) {
            throw new IllegalArgumentException("Лимиты импорта должны быть положительными");
        }
    }

    public static ImportLimits defaults() {
        return new ImportLimits(20L * 1024 * 1024, 50_000);
    }
}
