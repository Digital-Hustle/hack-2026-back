package ru.cashflow.authprofile.util.time;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
public class ApplicationClock {

    private final Clock clock;

    public Instant now() {
        return clock.instant();
    }

    public Instant nowAtSecond() {
        return now().truncatedTo(ChronoUnit.SECONDS);
    }
}
