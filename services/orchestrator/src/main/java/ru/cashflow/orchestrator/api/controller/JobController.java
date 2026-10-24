package ru.cashflow.orchestrator.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.CompanyDtos.ItemsRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.jobs.application.JobService;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.Job;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}")
public class JobController {

    private final JobService service;
    private final ru.cashflow.orchestrator.features.jobs.application.SerializationRetry retry;
    private final ObjectMapper json;
    private final ru.cashflow.orchestrator.features.jobs.application.BacktestJobService backtests;
    private final ru.cashflow.orchestrator.features.jobs.application.RecurringDetectionJobService
            detections;

    public JobController(
            JobService service,
            ru.cashflow.orchestrator.features.jobs.application.SerializationRetry retry,
            ObjectMapper json,
            ru.cashflow.orchestrator.features.jobs.application.BacktestJobService backtests,
            ru.cashflow.orchestrator.features.jobs.application.RecurringDetectionJobService
                    detections) {
        this.service = service;
        this.retry = retry;
        this.json = json;
        this.backtests = backtests;
        this.detections = detections;
    }

    /**
     * 202 + jobId; the client polls the job (handoff §4: Forecast is synchronous, the job lives in
     * Orchestrator).
     */
    @PostMapping("/accounts/{accountId}/forecast-jobs")
    public ResponseEntity<JobRs> create(
            CurrentUser user,
            @PathVariable UUID companyId,
            @PathVariable UUID accountId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ForecastJobRq rq) {
        Job job =
                retry.run(
                        () ->
                                service.createForecast(
                                        user.userId(),
                                        companyId,
                                        accountId,
                                        key,
                                        rq.asOfDate(),
                                        rq.scope()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(of(job));
    }

    @GetMapping("/accounts/{accountId}/jobs")
    public ItemsRs<JobRs> list(
            CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId) {
        return new ItemsRs<>(
                service.list(user.userId(), companyId, accountId).stream().map(this::of).toList());
    }

    @PostMapping("/accounts/{accountId}/backtest-jobs")
    public ResponseEntity<JobRs> backtest(
            CurrentUser user,
            @PathVariable UUID companyId,
            @PathVariable UUID accountId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody BacktestJobRq rq) {
        var request =
                new ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.BacktestRqW(
                        accountId.toString(),
                        rq.firstOrigin().toString(),
                        rq.lastOrigin().toString(),
                        rq.stepDays(),
                        rq.scope(),
                        rq.windowWeeks(),
                        rq.evaluateRisk());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        of(
                                retry.run(
                                        () ->
                                                backtests.create(
                                                        user.userId(),
                                                        companyId,
                                                        accountId,
                                                        key,
                                                        rq.asOfDate(),
                                                        request))));
    }

    public record BacktestJobRq(
            @NotNull LocalDate asOfDate,
            @NotNull LocalDate firstOrigin,
            @NotNull LocalDate lastOrigin,
            @NotNull @jakarta.validation.constraints.Min(7) @jakarta.validation.constraints.Max(30)
                    Integer stepDays,
            @NotNull @Pattern(regexp = "^(HISTORY_ONLY|WITH_KNOWN_PLANS)$") String scope,
            @NotNull Integer windowWeeks,
            @NotNull Boolean evaluateRisk) {}

    @PostMapping("/accounts/{accountId}/recurring-detection-jobs")
    public ResponseEntity<JobRs> recurringDetection(
            CurrentUser user,
            @PathVariable UUID companyId,
            @PathVariable UUID accountId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody DetectionJobRq request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(
                        of(
                                retry.run(
                                        () ->
                                                detections.create(
                                                        user.userId(),
                                                        companyId,
                                                        accountId,
                                                        key,
                                                        request.asOfDate()))));
    }

    public record DetectionJobRq(@NotNull LocalDate asOfDate) {}

    @GetMapping("/jobs/{jobId}")
    public JobRs get(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID jobId) {
        return of(service.get(user.userId(), companyId, jobId));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public JobRs cancel(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID jobId) {
        return of(service.cancel(user.userId(), companyId, jobId));
    }

    private JobRs of(Job j) {
        JsonNode error = null;
        if (j.errorJson() != null) {
            try {
                error = json.readTree(j.errorJson());
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                error = null;
            }
        }
        return new JobRs(
                j.id(),
                j.accountId(),
                j.type().name(),
                j.status().name(),
                j.snapshotId(),
                j.status()
                                == ru.cashflow.orchestrator.features.jobs.model.JobTypes.JobStatus
                                        .SUCCEEDED
                        ? j.resultId()
                        : null,
                j.progress(),
                j.attempts(),
                error,
                j.origin().name(),
                j.createdAt(),
                j.startedAt(),
                j.finishedAt());
    }

    public record ForecastJobRq(
            @NotNull LocalDate asOfDate,
            @NotNull @Pattern(regexp = "^(HISTORY_ONLY|WITH_KNOWN_PLANS)$") String scope) {}

    public record JobRs(
            UUID id,
            UUID accountId,
            String type,
            String status,
            UUID snapshotId,
            UUID resultId,
            int progress,
            int attempts,
            JsonNode error,
            String origin,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt) {}
}
