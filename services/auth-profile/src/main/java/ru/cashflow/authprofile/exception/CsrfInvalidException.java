package ru.cashflow.authprofile.exception;

public class CsrfInvalidException extends DomainException {

    public CsrfInvalidException() {
        super("CSRF validation failed");
    }
}
