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
import ru.cashflow.orchestrator.features.settings.model.ForecastSettings;
import ru.cashflow.orchestrator.features.snapshot.application.BacktestSnapshotService;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;
import ru.cashflow.orchestrator.features.snapshot.command.SnapshotException;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.BacktestRqW;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class BacktestJobService {
    private final CompanyAccess access;
    private final BacktestSnapshotService snapshots;
    private final JobRepository jobs;
    private final IdempotencyRepository idempotency;
    private final Clock clock;

    public BacktestJobService(
            CompanyAccess access,
            BacktestSnapshotService snapshots,
            JobRepository jobs,
            IdempotencyRepository idempotency,
            Clock clock) {
        this.access = access;
        this.snapshots = snapshots;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Job create(
            UUID user,
            UUID company,
            UUID account,
            String key,
            LocalDate asOf,
            BacktestRqW request) {
        var scope = access.requireWrite(user, company);
        if (key == null || !key.matches("^[A-Za-z0-9_.:-]{8,128}$")) {
            throw new ApiException(400, "BAD_IDEMPOTENCY_KEY", "Некорректный Idempotency-Key");
        }
        String operation = "CREATE_BACKTEST_JOB";
        String hash = Fingerprint.sha256(account + "|" + asOf + "|" + request);
        var existing = idempotency.find(scope, operation, key);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(hash)) {
                throw ApiException.conflict(
                        "IDEMPOTENCY_KEY_REUSED", "Ключ использован с другим запросом");
            }
            return jobs.find(scope, existing.get().resourceId())
                    .orElseThrow(ApiException::notFound);
        }
        var defaults = ForecastSettings.defaults();
        var settings =
                new ForecastSettings(
                        request.windowWeeks(),
                        30,
                        defaults.bufferAmount(),
                        defaults.scenarioCount(),
                        defaults.minCalibrationOrigins(),
                        7,
                        defaults.algorithm());
        try {
            var built = snapshots.build(scope, account, asOf, request, settings);
            var stored = snapshots.store(scope, built);
            var now = clock.instant();
            var job =
                    new Job(
                            UUID.randomUUID(),
                            company,
                            account,
                            JobType.BACKTEST,
                            JobStatus.QUEUED,
                            stored.id(),
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
            if (!idempotency.insert(scope, operation, key, hash, job.id(), now)) {
                throw ApiException.conflict("CONCURRENT_REQUEST", "Повторите запрос");
            }
            jobs.insert(scope, job);
            idempotency.audit(scope, "BACKTEST_JOB_CREATED", "JOB", job.id(), now);
            return jobs.find(scope, job.id()).orElseThrow(ApiException::notFound);
        } catch (SnapshotException exception) {
            throw ApiException.unprocessable(exception.code(), exception.getMessage());
        }
    }
}
