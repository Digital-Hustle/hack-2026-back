package ru.cashflow.orchestrator.features.forecast.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.forecast.gateway.ForecastException;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.Job;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.JobType;
import ru.cashflow.orchestrator.features.recurring.application.RecurringService;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.BusinessDayRule;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.DetectedCandidate;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Frequency;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Schedule;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository.StoredSnapshot;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class RecurringDetectionResultHandler implements ResultHandler {
    private final RecurringService recurring;
    private final ru.cashflow.orchestrator.features.companies.application.CompanyAccess access;
    private final ru.cashflow.orchestrator.features.companies.repository.CompanyRepository
            companies;
    private final ru.cashflow.orchestrator.features.jobs.repository.JobRepository jobs;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public RecurringDetectionResultHandler(
            RecurringService recurring,
            ru.cashflow.orchestrator.features.companies.application.CompanyAccess access,
            ru.cashflow.orchestrator.features.companies.repository.CompanyRepository companies,
            ru.cashflow.orchestrator.features.jobs.repository.JobRepository jobs,
            TransactionTemplate tx,
            ObjectMapper json,
            Clock clock) {
        this.recurring = recurring;
        this.access = access;
        this.companies = companies;
        this.jobs = jobs;
        this.tx = tx;
        this.json = json;
        this.clock = clock;
    }

    public void accept(Job job, int attempt, String owner, StoredSnapshot snapshot, String body) {
        if (job.type() != JobType.DETECT_RECURRING) {
            throw new ForecastException(
                    ForecastException.Kind.PERMANENT,
                    "RESULT_VALIDATOR_MISSING",
                    "Unsupported result type",
                    null);
        }
        JsonNode response = read(body);
        if (response == null || !response.isObject()
                || !response.path("detectorVersion").isTextual()
                || response.path("detectorVersion").asText().isBlank()) {
            throw invalid("detectorVersion");
        }
        String detectorVersion = response.path("detectorVersion").asText();
        List<DetectedCandidate> candidates = parse(response, job.accountId(), snapshot, detectorVersion);
        Boolean stored;
        try {
            stored = tx.execute(status -> {
                var scope = access.requireWrite(job.createdBy(), job.companyId());
                // Serialize completions with user decisions and financial dataVersion changes.
                var company = companies.lockCompany(scope).orElseThrow(ApiException::notFound);
                if (!jobs.succeed(job.id(), owner, attempt, clock.instant())) {
                    status.setRollbackOnly();
                    return false;
                }
                // A stale snapshot or an older completed detection must never overwrite newer candidates.
                if (company.dataVersion() != snapshot.inputVersion()
                        || jobs.hasNewerSuccessfulRecurringDetection(job.id())) {
                    return true;
                }
                recurring.mergeDetection(scope, job.accountId(), detectorVersion,
                        snapshot.inputVersion(), candidates);
                return true;
            });
        } catch (ApiException exception) {
            if ("INVALID_CANDIDATE".equals(exception.code())) {
                throw invalid("patterns");
            }
            throw new ForecastException(ForecastException.Kind.PERMANENT,
                    "RECURRING_ACCESS_CHANGED", "Detection access changed before completion", null);
        } catch (RuntimeException exception) {
            // Roll back the whole transaction, then let the worker retry/fail the leased job.
            throw new ForecastException(ForecastException.Kind.RETRYABLE,
                    "RECURRING_RESULT_STORAGE_FAILED", "Could not persist recurring detection result", null);
        }
        if (!Boolean.TRUE.equals(stored)) {
            throw new ForecastResultHandler.LeaseLostException(job.id());
        }
    }

    private List<DetectedCandidate> parse(JsonNode response, UUID accountId, StoredSnapshot snapshot,
                                          String detectorVersion) {
        JsonNode patterns = response.path("patterns");
        if (!patterns.isArray() || patterns.size() > 250_000) {
            throw invalid("patterns");
        }
        List<DetectedCandidate> result = new ArrayList<>();
        for (JsonNode pattern : patterns) {
            if (!pattern.isObject() || !pattern.path("status").isTextual()
                    || !"DETECTED".equals(pattern.path("status").asText())
                    || !accountId.toString().equals(pattern.path("accountId").asText())
                    || !pattern.path("detectorVersion").isTextual()
                    || !detectorVersion.equals(pattern.path("detectorVersion").asText())
                    || !pattern.path("inputDataVersion").isIntegralNumber()
                    || !pattern.path("inputDataVersion").canConvertToLong()
                    || pattern.path("inputDataVersion").asLong() != snapshot.inputVersion()
                    || !pattern.path("observations").isIntegralNumber()
                    || !pattern.path("observations").canConvertToInt()
                    || pattern.path("observations").asInt() < 3
                    || !pattern.path("amount").isTextual()
                    || !pattern.path("categoryCode").isTextual()
                    || pattern.path("categoryCode").asText().isBlank()
                    || pattern.path("categoryCode").asText().length() > 64
                    || !pattern.path("counterpartyKey").isTextual()
                    || pattern.path("counterpartyKey").asText().isBlank()
                    || pattern.path("counterpartyKey").asText().length() > 600
                    || !pattern.path("schedule").isObject()) {
                throw invalid("patterns");
            }
            JsonNode schedule = pattern.path("schedule");
            JsonNode weekdayValue = schedule.path("weekday");
            JsonNode dayValue = schedule.path("dayOfMonth");
            if ((!weekdayValue.isNull() && (!weekdayValue.isInt() || !weekdayValue.canConvertToInt()))
                    || (!dayValue.isNull() && (!dayValue.isInt() || !dayValue.canConvertToInt()))) {
                throw invalid("patterns[].schedule");
            }
            try {
                UUID.fromString(pattern.path("id").asText());
                Frequency frequency = Frequency.valueOf(schedule.path("frequency").asText());
                BusinessDayRule rule = BusinessDayRule.valueOf(schedule.path("businessDayRule").asText());
                Integer weekday = weekdayValue.isNull() ? null : weekdayValue.asInt();
                Integer day = dayValue.isNull() ? null : dayValue.asInt();
                BigDecimal amount = new BigDecimal(pattern.path("amount").asText());
                LocalDate firstSeen = LocalDate.parse(pattern.path("firstSeen").asText());
                LocalDate lastSeen = LocalDate.parse(pattern.path("lastSeen").asText());
                if (amount.signum() <= 0 || amount.scale() > 2 || amount.precision() > 19
                        || firstSeen.isAfter(lastSeen) || lastSeen.isAfter(snapshot.asOf())) {
                    throw invalid("patterns");
                }
                var candidate = new DetectedCandidate(
                        ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction
                                .valueOf(pattern.path("direction").asText()),
                        pattern.path("categoryCode").asText(),
                        pattern.path("counterpartyKey").asText(),
                        new Schedule(frequency, weekday, day, rule),
                        amount, pattern.path("observations").asInt(), firstSeen, lastSeen);
                if (!candidate.schedule().valid()) {
                    throw invalid("patterns[].schedule");
                }
                result.add(candidate);
            } catch (ForecastException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw invalid("patterns");
            }
        }
        return List.copyOf(result);
    }

    private ForecastException invalid(String field) {
        return new ForecastException(
                ForecastException.Kind.PERMANENT,
                "INVALID_RECURRING_RESULT",
                "Invalid recurring result field: " + field,
                null);
    }

    private JsonNode read(String body) {
        try {
            return body == null ? null : json.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            return null;
        }
    }
}
