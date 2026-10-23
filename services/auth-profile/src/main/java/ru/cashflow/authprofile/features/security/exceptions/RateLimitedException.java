package ru.cashflow.authprofile.features.security.exceptions;

import ru.cashflow.authprofile.exception.RetryableDomainException;

import java.time.Duration;

public class RateLimitedException extends RetryableDomainException {

    public RateLimitedException(Duration retryAfter) {
        super("Rate limited", retryAfter);
    }
}
