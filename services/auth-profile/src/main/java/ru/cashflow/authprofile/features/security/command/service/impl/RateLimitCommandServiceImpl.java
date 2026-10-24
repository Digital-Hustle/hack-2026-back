package ru.cashflow.authprofile.features.security.command.service.impl;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.authprofile.features.security.command.repository.RateLimitRepository;
import ru.cashflow.authprofile.features.security.command.service.RateLimitCommandService;
import ru.cashflow.authprofile.features.security.command.validator.RateLimitValidator;
import ru.cashflow.authprofile.features.security.exceptions.RateLimitedException;
import ru.cashflow.authprofile.features.security.model.RateLimitBucket;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RateLimitCommandServiceImpl implements RateLimitCommandService {

    private final RateLimitRepository limits;
    private final RateLimitValidator validator;

    @Override
    @Transactional(noRollbackFor = RateLimitedException.class)
    public void consume(String key, int limit, Duration window, Duration cooldown, Instant now) {
        var bucket = limits.lockOrCreate(key, now.plus(window), now);
        validator.validateAvailable(bucket, limit, now);
        boolean reset = !bucket.windowEnd().isAfter(now);
        limits.save(
                RateLimitBucket.builder()
                        .bucketKey(key)
                        .windowEnd(reset ? now.plus(window) : bucket.windowEnd())
                        .used(reset ? 1 : bucket.used() + 1)
                        .nextAllowedAt(now.plus(cooldown))
                        .build());
    }
}
