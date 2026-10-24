package ru.cashflow.authprofile.exception;

import java.time.Duration;

public abstract class RetryableDomainException extends DomainException {

    private final Duration retryAfter;

    protected RetryableDomainException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
