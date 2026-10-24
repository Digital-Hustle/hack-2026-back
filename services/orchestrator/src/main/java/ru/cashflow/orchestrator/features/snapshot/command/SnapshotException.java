package ru.cashflow.orchestrator.features.snapshot.command;

/** Input cannot be turned into a valid snapshot; the caller maps it to 422 without inventing missing data. */
public class SnapshotException extends RuntimeException {

    private final String code;

    public SnapshotException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
