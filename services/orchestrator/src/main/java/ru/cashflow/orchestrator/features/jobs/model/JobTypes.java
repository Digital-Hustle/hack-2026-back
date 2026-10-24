package ru.cashflow.orchestrator.features.jobs.model;

import java.time.Instant;
import java.util.UUID;

public final class JobTypes {

    private JobTypes() {
    }

    public enum JobType { FORECAST, BACKTEST, DETECT_RECURRING }

    public enum JobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    /**
     * resultId is fixed when the job is created; it becomes X-Calculation-Id of every attempt, so retries of the same
     * job always target the same result row and cannot create a second result (handoff §2, §5).
     */
    public record Job(UUID id, UUID companyId, UUID accountId, JobType type, JobStatus status, UUID snapshotId,
                      UUID resultId, int progress, String errorJson, String leaseOwner, Instant leaseUntil,
                      int attempts, int maxAttempts, Instant notBefore, UUID createdBy, Instant createdAt,
                      Instant startedAt, Instant finishedAt) {
    }

    /** A claimed job together with the lease token that every later write must present. */
    public record Lease(Job job, String owner, int attempt, Instant until) {
    }
}
