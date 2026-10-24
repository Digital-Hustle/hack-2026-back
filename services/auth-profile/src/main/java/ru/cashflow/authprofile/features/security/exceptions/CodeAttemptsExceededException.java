package ru.cashflow.authprofile.features.security.exceptions;

import ru.cashflow.authprofile.exception.RetryableDomainException;

import java.time.Duration;

public class CodeAttemptsExceededException extends RetryableDomainException {

    public CodeAttemptsExceededException(Duration retryAfter) {
        super("Code attempts exceeded", retryAfter);
    }
}
