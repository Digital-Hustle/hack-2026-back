package ru.cashflow.preprocessor.features.statements.model;

public final class CsvInputException extends RuntimeException {

    private final String code;
    private final int status;
    private final long sourceLine;

    public CsvInputException(String code, int status, String message, long sourceLine) {
        super(message);
        this.code = code;
        this.status = status;
        this.sourceLine = sourceLine;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public long sourceLine() {
        return sourceLine;
    }
}
