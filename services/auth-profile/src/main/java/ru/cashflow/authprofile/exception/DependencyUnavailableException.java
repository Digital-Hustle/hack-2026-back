package ru.cashflow.authprofile.exception;

public class DependencyUnavailableException extends SystemException {

    public DependencyUnavailableException() {
        super("Dependency unavailable");
    }

    public DependencyUnavailableException(Throwable cause) {
        this();
        initCause(cause);
    }
}
