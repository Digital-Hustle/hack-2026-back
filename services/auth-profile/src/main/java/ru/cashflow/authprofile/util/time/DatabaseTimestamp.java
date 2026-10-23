package ru.cashflow.authprofile.util.time;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class DatabaseTimestamp {

    public static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
