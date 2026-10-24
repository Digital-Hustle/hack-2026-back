package ru.cashflow.orchestrator.features.forecast.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import ru.cashflow.orchestrator.features.forecast.gateway.ForecastClient;
import ru.cashflow.orchestrator.features.forecast.gateway.ForecastClient.ForecastResponse;
import ru.cashflow.orchestrator.features.forecast.gateway.ForecastException;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.Job;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.JobType;
import ru.cashflow.orchestrator.features.jobs.repository.JobRepository;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository.StoredSnapshot;

/**
 * One worker step: claim → verify the stored snapshot → call Forecast after commit → hand the response to a
 * ResultHandler (task 10 validates and persists atomically). No transaction is open during the HTTP call.
 */
@Service
public class ForecastWorker {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastWorker.class);

    private final JobRepository jobs;
    private final SnapshotRepository snapshots;
    private final ForecastClient client;
    private final ResultHandler handler;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration lease;
    private final String owner;

    public ForecastWorker(JobRepository jobs, SnapshotRepository snapshots, ForecastClient client,
                          ResultHandler handler, TransactionTemplate tx, ObjectMapper json, Clock clock,
                          @Value("${orchestrator.jobs.lease:60s}") Duration lease) {
        this.jobs = jobs;
        this.snapshots = snapshots;
        this.client = client;
        this.handler = handler;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
        this.lease = lease;
        this.owner = "worker-" + UUID.randomUUID();
    }

    /** @return true if a job was processed (whatever its outcome), false if the queue was empty */
    public boolean runOnce() {
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> jobs.failExhausted(now));
        Optional<Job> claimed = tx.execute(s -> jobs.claim(owner, now, now.plus(lease)));
        if (claimed == null || claimed.isEmpty()) {
            return false;
        }
        Job job = claimed.get();
        int attempt = job.attempts();
        StoredSnapshot snapshot = snapshots.findForWorker(job.companyId(), job.snapshotId()).orElse(null);
        if (snapshot == null || !snapshot.accountId().equals(job.accountId())) {
            fail(job, attempt, "SNAPSHOT_MISMATCH", "Snapshot задания не найден или относится к другому счёту");
            return true;
        }
        JsonNode payload = read(snapshot.payload());
        if (payload == null || !Fingerprint.of(payload).equals(snapshot.fingerprint())) {
            fail(job, attempt, "FINGERPRINT_MISMATCH", "Сохранённый snapshot не совпадает со своим fingerprint");
            return true;
        }
        String path = switch (job.type()) {
            case FORECAST -> ForecastClient.PATH_CALCULATE;
            case BACKTEST -> ForecastClient.PATH_BACKTEST;
            case DETECT_RECURRING -> ForecastClient.PATH_RECURRING;
        };
        UUID calculationId = job.type() == JobType.DETECT_RECURRING ? job.id() : job.resultId();
        ForecastResponse response;
        try {
            response = client.post(path, canonicalBody(payload), job.id(), calculationId);
        } catch (ForecastException exception) {
            handleFailure(job, attempt, exception);
            return true;
        }
        try {
            classify(response);
            handler.accept(job, attempt, owner, snapshot, response.body());
            LOG.info("forecast_job_succeeded jobId={} attempt={}", job.id(), attempt);
        } catch (ForecastException exception) {
            handleFailure(job, attempt, exception);
        } catch (ForecastResultHandler.LeaseLostException exception) {
            LOG.info("forecast_job_late_response_discarded jobId={} attempt={}", job.id(), attempt);
        }
        return true;
    }

    private void classify(ForecastResponse response) {
        int status = response.status();
        if (status == 200) {
            return;
        }
        if (status == 429 || status == 503 || status >= 500) {
            throw new ForecastException(ForecastException.Kind.RETRYABLE, "FORECAST_UNAVAILABLE",
                    "Forecast временно недоступен", response.retryAfterSeconds());
        }
        String code = "FORECAST_REJECTED";
        JsonNode problem = read(response.body());
        if (problem != null && problem.path("code").isTextual() && problem.path("code").asText().matches("^[A-Z_]{1,64}$")) {
            code = problem.path("code").asText();
        }
        throw new ForecastException(ForecastException.Kind.PERMANENT, code,
                "Forecast отклонил входные данные (HTTP " + status + ")", null);
    }

    private void handleFailure(Job job, int attempt, ForecastException exception) {
        if (exception.kind() == ForecastException.Kind.RETRYABLE && attempt < job.maxAttempts()) {
            long backoff = exception.retryAfterSeconds() != null ? exception.retryAfterSeconds()
                    : Math.min(60, 1L << Math.min(attempt, 5));
            String error = error(exception.code(), exception.getMessage());
            tx.executeWithoutResult(s -> jobs.retryLater(job.id(), owner, attempt, error,
                    clock.instant().plusSeconds(backoff)));
            LOG.info("forecast_job_retry jobId={} attempt={} code={}", job.id(), attempt, exception.code());
            return;
        }
        fail(job, attempt, exception.code(), exception.getMessage());
    }

    private void fail(Job job, int attempt, String code, String message) {
        String error = error(code, message);
        tx.executeWithoutResult(s -> jobs.fail(job.id(), owner, attempt, error, clock.instant()));
        LOG.info("forecast_job_failed jobId={} attempt={} code={}", job.id(), attempt, code);
    }

    /** Sends the stored snapshot exactly as persisted (minus jsonb key reordering), so retries send the same bytes. */
    private String canonicalBody(JsonNode payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String error(String code, String message) {
        try {
            return json.writeValueAsString(java.util.Map.of("code", code, "detail", message));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JsonNode read(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return null;
        }
    }

    public String owner() {
        return owner;
    }
}
