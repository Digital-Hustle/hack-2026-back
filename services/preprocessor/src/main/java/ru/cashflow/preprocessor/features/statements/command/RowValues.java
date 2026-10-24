package ru.cashflow.preprocessor.features.statements.command;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

final class RowValues {

    private static final Pattern MONEY = Pattern.compile("-?[0-9]+(?:\\.[0-9]{1,2})?");
    private static final Pattern ID = Pattern.compile("[\\p{L}\\p{N}_.:-]{1,128}");
    private static final Pattern INN = Pattern.compile("(?:[0-9]{10}|[0-9]{12})");
    private static final BigDecimal MONEY_BOUND = new BigDecimal("100000000000000000");

    private RowValues() {}

    static String id(CsvRows.Row row, String field) {
        String value = row.value(field);
        if (!ID.matcher(value).matches()) {
            throw invalid(field, "Нужен непустой идентификатор длиной до 128 символов");
        }
        return value;
    }

    static String text(CsvRows.Row row, String field, int limit, boolean required) {
        String value = row.value(field);
        if ((required && value.isEmpty()) || value.length() > limit
                || value.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t')) {
            throw invalid(field, "Поле пусто, слишком длинное или содержит недопустимые символы");
        }
        return value;
    }

    static String inn(CsvRows.Row row, String field) {
        String value = row.value(field);
        if (!value.isEmpty() && !INN.matcher(value).matches()) {
            throw invalid(field, "ИНН должен содержать 10 или 12 цифр; пустое значение допустимо");
        }
        return value;
    }

    static LocalDate date(CsvRows.Row row, String field, LocalDate today, boolean required) {
        String value = row.value(field);
        if (!required && value.isEmpty()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1 || date.getYear() > 9999 || date.isAfter(today) || !date.toString().equals(value)) {
                throw invalid(field, "Дата должна быть YYYY-MM-DD и не находиться в будущем");
            }
            return date;
        } catch (DateTimeParseException exception) {
            throw invalid(field, "Нужна существующая дата в формате YYYY-MM-DD");
        }
    }

    static BigDecimal money(CsvRows.Row row, String field, boolean positive, boolean required) {
        String value = row.value(field);
        if (!required && value.isEmpty()) {
            return null;
        }
        if (!MONEY.matcher(value).matches() || value.length() > 21) {
            throw invalid(field, "Нужна десятичная сумма с точкой и не более двух знаков после неё");
        }
        BigDecimal amount = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
        if ((positive && amount.signum() <= 0) || amount.abs().compareTo(MONEY_BOUND) >= 0) {
            throw invalid(field, positive ? "Сумма должна быть положительной и помещаться в NUMERIC(19,2)"
                    : "Сумма должна помещаться в NUMERIC(19,2)");
        }
        return amount;
    }

    static Integer count(CsvRows.Row row, String field) {
        String value = row.value(field);
        if (value.isEmpty()) {
            return null;
        }
        try {
            if (!value.matches("[0-9]{1,10}")) {
                throw invalid(field, "Нужно неотрицательное целое число");
            }
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            throw invalid(field, "Число операций превышает допустимый диапазон");
        }
    }

    static InvalidRow invalid(String field, String message) {
        return new InvalidRow(field, message);
    }

    static final class InvalidRow extends RuntimeException {
        private final String field;

        InvalidRow(String field, String message) {
            super(message);
            this.field = field;
        }

        String field() {
            return field;
        }
    }
}
