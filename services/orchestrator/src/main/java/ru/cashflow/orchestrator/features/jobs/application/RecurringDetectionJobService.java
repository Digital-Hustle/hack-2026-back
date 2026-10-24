package ru.cashflow.orchestrator.features.jobs.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.Job;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.JobStatus;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.JobType;
import ru.cashflow.orchestrator.features.jobs.repository.IdempotencyRepository;
import ru.cashflow.orchestrator.features.jobs.repository.JobRepository;
import ru.cashflow.orchestrator.features.snapshot.application.SnapshotService;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;
import ru.cashflow.orchestrator.features.snapshot.command.SnapshotException;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class RecurringDetectionJobService {
    private final CompanyAccess access;
    private final SnapshotService snapshots;
    private final SnapshotRepository snapshotRepository;
    private final JobRepository jobs;
    private final IdempotencyRepository idempotency;
    private final Clock clock;

    public RecurringDetectionJobService(
            CompanyAccess access,
            SnapshotService snapshots,
            SnapshotRepository snapshotRepository,
            JobRepository jobs,
            IdempotencyRepository idempotency,
            Clock clock) {
        this.access = access;
        this.snapshots = snapshots;
        this.snapshotRepository = snapshotRepository;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Job create(UUID user, UUID company, UUID account, String key, LocalDate asOf) {
        var scope = access.requireWrite(user, company);
        if (key == null || !key.matches("^[A-Za-z0-9_.:-]{8,128}$")) {
            throw new ApiException(400, "BAD_IDEMPOTENCY_KEY", "Некорректный Idempotency-Key");
        }
        String hash = Fingerprint.sha256(account + "|" + asOf);
        var existing = idempotency.find(scope, "CREATE_RECURRING_DETECTION_JOB", key);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(hash)) {
                throw ApiException.conflict(
                        "IDEMPOTENCY_KEY_REUSED", "Ключ использован с другим запросом");
            }
            return jobs.find(scope, existing.get().resourceId())
                    .orElseThrow(ApiException::notFound);
        }
        try {
            var built = snapshots.buildRecurringDetection(scope, account, asOf);
            snapshotRepository.insert(
                    scope,
                    new SnapshotRepository.StoredSnapshot(
                            built.id(),
                            account,
                            "DETECT_RECURRING",
                            asOf,
                            built.inputVersion(),
                            built.fingerprint(),
                            built.payload(),
                            built.provenance(),
                            clock.instant()));
            Instant now = clock.instant();
            var job =
                    new Job(
                            UUID.randomUUID(),
                            company,
                            account,
                            JobType.DETECT_RECURRING,
                            JobStatus.QUEUED,
                            built.id(),
                            UUID.randomUUID(),
                            0,
                            null,
                            null,
                            null,
                            0,
                            3,
                            now,
                            user,
                            now,
                            null,
                            null);
            if (!idempotency.insert(
                    scope, "CREATE_RECURRING_DETECTION_JOB", key, hash, job.id(), now)) {
                throw ApiException.conflict("CONCURRENT_REQUEST", "Повторите запрос");
            }
            jobs.insert(scope, job);
            idempotency.audit(scope, "RECURRING_DETECTION_JOB_CREATED", "JOB", job.id(), now);
            return jobs.find(scope, job.id()).orElseThrow(ApiException::notFound);
        } catch (SnapshotException exception) {
            throw ApiException.unprocessable(exception.code(), exception.getMessage());
        }
    }
}
