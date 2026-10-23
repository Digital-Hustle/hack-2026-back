package ru.cashflow.authprofile.features.security.command.service;

import java.time.Duration;
import java.time.Instant;

public interface RateLimitCommandService {

    void consume(String key, int limit, Duration window, Duration cooldown, Instant now);
}
