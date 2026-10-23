package ru.cashflow.authprofile.features.security.command.repository;

import static ru.cashflow.authprofile.jooq.Tables.RATE_LIMITS;

import lombok.RequiredArgsConstructor;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ru.cashflow.authprofile.features.security.model.RateLimitBucket;

import java.time.Instant;

@Repository
@RequiredArgsConstructor
public class RateLimitRepository {

    private final DSLContext dsl;

    public RateLimitBucket lockOrCreate(String key, Instant windowEnd, Instant now) {
        var initial =
                RateLimitBucket.builder()
                        .bucketKey(key)
                        .windowEnd(windowEnd)
                        .nextAllowedAt(now)
                        .build();

        dsl.insertInto(RATE_LIMITS)
                .set(dsl.newRecord(RATE_LIMITS, initial))
                .onConflict(RATE_LIMITS.BUCKET_KEY)
                .doNothing()
                .execute();

        return dsl.select(
                        RATE_LIMITS.BUCKET_KEY,
                        RATE_LIMITS.WINDOW_END,
                        RATE_LIMITS.USED,
                        RATE_LIMITS.NEXT_ALLOWED_AT)
                .from(RATE_LIMITS)
                .where(RATE_LIMITS.BUCKET_KEY.eq(key))
                .forUpdate()
                .fetchSingleInto(RateLimitBucket.class);
    }

    public void save(RateLimitBucket bucket) {
        dsl.update(RATE_LIMITS)
                .set(dsl.newRecord(RATE_LIMITS, bucket))
                .where(RATE_LIMITS.BUCKET_KEY.eq(bucket.bucketKey()))
                .execute();
    }
}
