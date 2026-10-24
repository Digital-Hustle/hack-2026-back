package ru.cashflow.authprofile.units;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import ru.cashflow.authprofile.features.security.command.repository.RateLimitRepository;
import ru.cashflow.authprofile.features.security.command.service.impl.RateLimitCommandServiceImpl;
import ru.cashflow.authprofile.features.security.command.validator.RateLimitValidator;
import ru.cashflow.authprofile.features.security.exceptions.RateLimitedException;
import ru.cashflow.authprofile.features.security.model.RateLimitBucket;

import java.time.Duration;
import java.time.Instant;

class RateLimitCommandServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(1);
    private final RateLimitRepository repository = mock(RateLimitRepository.class);
    private final RateLimitCommandServiceImpl service =
            new RateLimitCommandServiceImpl(repository, new RateLimitValidator());

    @Test
    void cooldownDoesNotSpendAnotherAttempt() {
        // given
        when(repository.lockOrCreate("bucket", NOW.plus(WINDOW), NOW))
                .thenReturn(
                        RateLimitBucket.builder()
                                .bucketKey("bucket")
                                .windowEnd(NOW.plusSeconds(600))
                                .used(1)
                                .nextAllowedAt(NOW.plusSeconds(25))
                                .build());
        // when
        var failure =
                assertThrows(
                        RateLimitedException.class,
                        () -> service.consume("bucket", 5, WINDOW, Duration.ofMinutes(1), NOW));
        // then
        assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(25));
        verify(repository, never()).save(any());
    }

    @Test
    void exhaustedWindowReportsRemainingTime() {
        // given
        when(repository.lockOrCreate("bucket", NOW.plus(WINDOW), NOW))
                .thenReturn(
                        RateLimitBucket.builder()
                                .bucketKey("bucket")
                                .windowEnd(NOW.plusSeconds(600))
                                .used(5)
                                .nextAllowedAt(NOW)
                                .build());
        // when
        var failure =
                assertThrows(
                        RateLimitedException.class,
                        () -> service.consume("bucket", 5, WINDOW, Duration.ZERO, NOW));
        // then
        assertThat(failure.retryAfter()).isEqualTo(Duration.ofMinutes(10));
        verify(repository, never()).save(any());
    }

    @Test
    void expiredWindowStartsWithOneAttempt() {
        // given
        when(repository.lockOrCreate("bucket", NOW.plus(WINDOW), NOW))
                .thenReturn(
                        RateLimitBucket.builder()
                                .bucketKey("bucket")
                                .windowEnd(NOW)
                                .used(5)
                                .nextAllowedAt(NOW)
                                .build());
        // when
        service.consume("bucket", 5, WINDOW, Duration.ofMinutes(1), NOW);
        // then
        verify(repository)
                .save(
                        RateLimitBucket.builder()
                                .bucketKey("bucket")
                                .windowEnd(NOW.plus(WINDOW))
                                .used(1)
                                .nextAllowedAt(NOW.plusSeconds(60))
                                .build());
    }
}
