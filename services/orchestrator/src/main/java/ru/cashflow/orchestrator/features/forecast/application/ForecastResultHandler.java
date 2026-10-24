package ru.cashflow.orchestrator.features.forecast.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import ru.cashflow.orchestrator.features.forecast.command.ForecastResultValidator;
import ru.cashflow.orchestrator.features.forecast.gateway.ForecastException;
import ru.cashflow.orchestrator.features.forecast.model.ForecastResult;
import ru.cashflow.orchestrator.features.forecast.repository.ForecastRepository;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.Job;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.JobType;
import ru.cashflow.orchestrator.features.jobs.repository.JobRepository;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository.StoredSnapshot;

/**
 * Validates a FORECAST response against its job and stored snapshot, then stores forecast, days and gaps and marks
 * the job SUCCEEDED in ONE transaction, fenced by lease owner and attempt (handoff §6). A late response of an expired
 * worker cannot overwrite the result of a newer one; a repeated attempt cannot create a second result.
 */
@Component
@Primary
public class ForecastResultHandler implements ResultHandler {

    private final ForecastRepository forecasts;
    private final JobRepository jobs;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public ForecastResultHandler(ForecastRepository forecasts, JobRepository jobs, TransactionTemplate tx,
                                 ObjectMapper json, Clock clock) {
        this.forecasts = forecasts;
        this.jobs = jobs;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void accept(Job job, int attempt, String leaseOwner, StoredSnapshot snapshot, String responseBody) {
        if (job.type() != JobType.FORECAST) {
            throw new ForecastException(ForecastException.Kind.PERMANENT, "RESULT_VALIDATOR_MISSING",
                    "Для этого типа задания нет проверки результата", null);
        }
        JsonNode input = read(snapshot.payload());
        JsonNode response = read(responseBody);
        if (response == null) {
            throw new ForecastException(ForecastException.Kind.PERMANENT, "INVALID_FORECAST_RESULT",
                    "Ответ Forecast не является JSON", null);
        }
        var expected = new ForecastResultValidator.Expected(job.resultId(), job.id(), snapshot.id(), job.companyId(),
                job.accountId(), LocalDate.parse(input.path("asOfDate").asText()), input.path("scope").asText(),
                input.path("algorithmVersion").asText(), input.path("inputDataVersion").asLong(),
                new BigDecimal(input.path("startBalance").asText()),
                LocalDate.parse(input.path("startBalanceDate").asText()),
                new BigDecimal(input.path("settings").path("bufferAmount").asText()),
                input.path("settings").path("minCalibrationOrigins").asInt(), input.path("calibrationOrigins").size());
        ForecastResult result = ForecastResultValidator.validate(response, expected);
        Boolean stored = tx.execute(status -> {
            if (!jobs.succeed(job.id(), leaseOwner, attempt, clock.instant())) {
                status.setRollbackOnly();
                return false;
            }
            forecasts.insert(result, json.valueToTree(input.path("settings")).toString(), responseBody,
                    clock.instant());
            return true;
        });
        if (!Boolean.TRUE.equals(stored)) {
            throw new LeaseLostException(job.id());
        }
    }

    private JsonNode read(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return null;
        }
    }

    /** The lease moved to another attempt; this response is discarded without touching the stored result. */
    public static final class LeaseLostException extends RuntimeException {
        public LeaseLostException(UUID jobId) {
            super("lease lost for job " + jobId);
        }
    }
}
