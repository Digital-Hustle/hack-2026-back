package ru.cashflow.forecast.features.shared.model;

/** Safe, value-free input diagnostics. Never contains payment amounts or request bodies. */
public class InputException extends RuntimeException {
    private final String code;
    private final String field;

    public InputException(String code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String code() { return code; }
    public String field() { return field; }
}
