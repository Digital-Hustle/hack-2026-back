package ru.cashflow.authprofile.features.security.command.validator;

import org.springframework.stereotype.Component;

import ru.cashflow.authprofile.features.security.exceptions.RateLimitedException;
import ru.cashflow.authprofile.features.security.model.RateLimitBucket;

import java.time.Duration;
import java.time.Instant;

@Component
public class RateLimitValidator {

    public void validateAvailable(RateLimitBucket bucket, int limit, Instant now) {
        if (bucket.nextAllowedAt().isAfter(now)) {
            throw new RateLimitedException(Duration.between(now, bucket.nextAllowedAt()));
        }
        if (bucket.windowEnd().isAfter(now) && bucket.used() >= limit) {
            throw new RateLimitedException(Duration.between(now, bucket.windowEnd()));
        }
    }
}
