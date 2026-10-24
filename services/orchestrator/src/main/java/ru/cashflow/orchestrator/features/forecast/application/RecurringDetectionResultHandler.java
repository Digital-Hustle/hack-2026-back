package ru.cashflow.orchestrator.features.forecast.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

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
        List<DetectedCandidate> candidates = parse(response, job.accountId());
        String detectorVersion = response.path("detectorVersion").asText("");
        if (detectorVersion.isBlank()) {
            throw invalid("detectorVersion");
        }
        Boolean stored =
                tx.execute(
                        status -> {
                            var scope = access.requireWrite(job.createdBy(), job.companyId());
                            var company = companies.findCompany(scope).orElseThrow();
                            if (!jobs.succeed(job.id(), owner, attempt, clock.instant())) {
                                status.setRollbackOnly();
                                return false;
                            }
                            recurring.mergeDetection(
                                    scope,
                                    job.accountId(),
                                    detectorVersion,
                                    snapshot.inputVersion(),
                                    candidates);
                            return true;
                        });
        if (!Boolean.TRUE.equals(stored)) {
            throw new ForecastResultHandler.LeaseLostException(job.id());
        }
    }

    private List<DetectedCandidate> parse(JsonNode response, UUID accountId) {
        if (response == null || !response.isObject() || !response.path("patterns").isArray()) {
            throw invalid("patterns");
        }
        var result = new ArrayList<DetectedCandidate>();
        for (JsonNode pattern : response.path("patterns")) {
            if (!"DETECTED".equals(pattern.path("status").asText())
                    || !accountId.toString().equalsIgnoreCase(pattern.path("accountId").asText())
                    || !pattern.path("amount").isTextual()
                    || pattern.path("observations").asInt() < 3) {
                throw invalid("patterns");
            }
            JsonNode schedule = pattern.path("schedule");
            try {
                Frequency frequency = Frequency.valueOf(schedule.path("frequency").asText());
                BusinessDayRule rule =
                        BusinessDayRule.valueOf(schedule.path("businessDayRule").asText());
                Integer weekday =
                        schedule.path("weekday").isNull() ? null : schedule.path("weekday").asInt();
                Integer day =
                        schedule.path("dayOfMonth").isNull()
                                ? null
                                : schedule.path("dayOfMonth").asInt();
                var candidate =
                        new DetectedCandidate(
                                ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction
                                        .valueOf(pattern.path("direction").asText()),
                                pattern.path("categoryCode").asText(),
                                nullable(pattern, "counterpartyKey"),
                                new Schedule(frequency, weekday, day, rule),
                                new BigDecimal(pattern.path("amount").asText()),
                                pattern.path("observations").asInt(),
                                LocalDate.parse(pattern.path("firstSeen").asText()),
                                LocalDate.parse(pattern.path("lastSeen").asText()));
                if (!candidate.schedule().valid()) {
                    throw invalid("patterns[].schedule");
                }
                result.add(candidate);
            } catch (IllegalArgumentException exception) {
                throw invalid("patterns");
            }
        }
        return List.copyOf(result);
    }

    private String nullable(JsonNode node, String field) {
        return node.path(field).isNull() ? null : node.path(field).asText();
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
