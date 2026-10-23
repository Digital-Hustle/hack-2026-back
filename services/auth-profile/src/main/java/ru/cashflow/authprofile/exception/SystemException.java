package ru.cashflow.authprofile.exception;

public abstract class SystemException extends RuntimeException {

    protected SystemException(String message) {
        super(message);
    }
}
