package ru.cashflow.orchestrator.features.jobs.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.Job;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.JobStatus;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.JobType;
import ru.cashflow.orchestrator.features.jobs.repository.IdempotencyRepository;
import ru.cashflow.orchestrator.features.jobs.repository.JobRepository;
import ru.cashflow.orchestrator.features.snapshot.application.SnapshotService;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;
import ru.cashflow.orchestrator.features.snapshot.command.SnapshotException;

/**
 * Creates forecast jobs (handoff §3, §5 steps 1–11): access check, snapshot and QUEUED job are committed in one
 * REPEATABLE READ transaction. Forecast is called later by the worker, never inside this transaction.
 */
@Service
public class JobService {

    public static final String OPERATION_FORECAST = "CREATE_FORECAST_JOB";
    private static final Logger LOG = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobs;
    private final IdempotencyRepository idempotency;
    private final SnapshotService snapshots;
    private final CompanyAccess access;
    private final Clock clock;

    public JobService(JobRepository jobs, IdempotencyRepository idempotency, SnapshotService snapshots,
                      CompanyAccess access, Clock clock) {
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.snapshots = snapshots;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Job createForecast(UUID userId, UUID companyId, UUID accountId, String idempotencyKey, LocalDate asOf,
                              String scope) {
        CompanyScope companyScope = access.requireWrite(userId, companyId);
        validateKey(idempotencyKey);
        String requestHash = Fingerprint.sha256(accountId + "|" + asOf + "|" + scope);
        var existing = idempotency.find(companyScope, OPERATION_FORECAST, idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(requestHash)) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "Ключ идемпотентности уже использован с другим запросом");
            }
            return jobs.find(companyScope, existing.get().resourceId()).orElseThrow(ApiException::notFound);
        }
        SnapshotService.Built built;
        try {
            built = snapshots.buildCurrent(companyScope, accountId, asOf, scope, List.of());
        } catch (SnapshotException exception) {
            throw ApiException.unprocessable(exception.code(), exception.getMessage());
        }
        var stored = snapshots.store(companyScope, "FORECAST", built);
        Instant now = clock.instant();
        var job = new Job(UUID.randomUUID(), companyScope.companyId(), accountId, JobType.FORECAST, JobStatus.QUEUED,
                stored.id(), UUID.randomUUID(), 0, null, null, null, 0, 3, now, userId, now, null, null);
        if (!idempotency.insert(companyScope, OPERATION_FORECAST, idempotencyKey, requestHash, job.id(), now)) {
            throw ApiException.conflict("CONCURRENT_REQUEST", "Повторите запрос: он уже выполняется");
        }
        jobs.insert(companyScope, job);
        idempotency.audit(companyScope, "FORECAST_JOB_CREATED", "JOB", job.id(), now);
        LOG.info("forecast_job_created jobId={}", job.id());
        return jobs.find(companyScope, job.id()).orElseThrow(ApiException::notFound);
    }

    public Job get(UUID userId, UUID companyId, UUID jobId) {
        return jobs.find(access.requireRead(userId, companyId), jobId).orElseThrow(ApiException::notFound);
    }

    public List<Job> list(UUID userId, UUID companyId, UUID accountId) {
        return jobs.list(access.requireRead(userId, companyId), accountId, 100);
    }

    @Transactional
    public Job cancel(UUID userId, UUID companyId, UUID jobId) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        Job job = jobs.find(scope, jobId).orElseThrow(ApiException::notFound);
        if (job.status() == JobStatus.QUEUED && !jobs.cancel(scope, jobId, clock.instant())) {
            throw ApiException.conflict("JOB_STARTED", "Задание уже выполняется");
        }
        return jobs.find(scope, jobId).orElseThrow(ApiException::notFound);
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("^[A-Za-z0-9_.:-]{8,128}$")) {
            throw new ApiException(400, "BAD_IDEMPOTENCY_KEY", "Idempotency-Key: 8..128 символов [A-Za-z0-9_.:-]");
        }
    }
}
