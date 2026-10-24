package ru.cashflow.orchestrator.features.recurring.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.DetectedCandidate;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.PatternStatus;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.RecurringPattern;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.StatusSource;
import ru.cashflow.orchestrator.features.recurring.repository.RecurringRepository;

/**
 * Detection results are only candidates. A user CONFIRMED/EXCLUDED decision is never overwritten by a later
 * detection run, and only CONFIRMED patterns are ever sent to Forecast (handoff §2).
 */
@Service
public class RecurringService {

    private final RecurringRepository patterns;
    private final CompanyRepository companies;
    private final CompanyAccess access;
    private final ObjectMapper json;
    private final Clock clock;

    public RecurringService(RecurringRepository patterns, CompanyRepository companies, CompanyAccess access,
                            ObjectMapper json, Clock clock) {
        this.patterns = patterns;
        this.companies = companies;
        this.access = access;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Merges one detection result. Ambiguous candidates (same stable key twice in a run) are skipped instead of
     * guessed. Updating schedule/amount of a USER-decided pattern keeps the decision; it does not change what Forecast
     * receives until the user reviews it, because that data is part of the confirmed rule.
     */
    @Transactional
    public MergeResult mergeDetection(CompanyScope scope, UUID accountId, String detectorVersion,
                                      long inputDataVersion, List<DetectedCandidate> candidates) {
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        Map<String, DetectedCandidate> byKey = new LinkedHashMap<>();
        java.util.Set<String> ambiguous = new java.util.HashSet<>();
        for (DetectedCandidate candidate : candidates) {
            if (!candidate.schedule().valid() || candidate.amount().signum() <= 0
                    || candidate.firstSeen().isAfter(candidate.lastSeen())) {
                throw ApiException.unprocessable("INVALID_CANDIDATE", "Кандидат регулярности нарушает инварианты");
            }
            String key = RecurringTypes.stableKey(accountId, candidate.categoryCode(), candidate.direction(),
                    candidate.counterpartyKey());
            if (byKey.putIfAbsent(key, candidate) != null) {
                ambiguous.add(key);
            }
        }
        ambiguous.forEach(byKey::remove);
        Instant now = clock.instant();
        int created = 0;
        int refreshed = 0;
        int keptDecisions = 0;
        for (var entry : byKey.entrySet()) {
            DetectedCandidate c = entry.getValue();
            Optional<RecurringPattern> existing = patterns.lockByKey(scope, accountId, entry.getKey());
            if (existing.isEmpty()) {
                var pattern = new RecurringPattern(UUID.randomUUID(), scope.companyId(), accountId, entry.getKey(),
                        c.direction(), c.categoryCode(), c.counterpartyKey(), PatternStatus.DETECTED,
                        StatusSource.DETECTOR, c.schedule(), c.amount().setScale(2), c.observations(), c.firstSeen(),
                        c.lastSeen(), detectorVersion, inputDataVersion, now, now, 0);
                patterns.insert(pattern);
                patterns.insertRevision(pattern, payload(pattern), null);
                created++;
                continue;
            }
            RecurringPattern current = existing.get();
            if (current.statusSource() == StatusSource.USER) {
                keptDecisions++;
                continue;
            }
            var next = new RecurringPattern(current.id(), current.companyId(), accountId, current.stableKey(),
                    current.direction(), current.categoryCode(), current.counterpartyKey(), PatternStatus.DETECTED,
                    StatusSource.DETECTOR, c.schedule(), c.amount().setScale(2), c.observations(), c.firstSeen(),
                    c.lastSeen(), detectorVersion, inputDataVersion, current.knownAt(), now, current.version() + 1);
            patterns.update(next, current.version());
            patterns.insertRevision(next, payload(next), null);
            refreshed++;
        }
        return new MergeResult(created, refreshed, keptDecisions, ambiguous.size());
    }

    /** User decision; CONFIRMED/EXCLUDED changes what Forecast receives, so it increments dataVersion (§3). */
    @Transactional
    public RecurringPattern decide(UUID userId, UUID companyId, UUID accountId, UUID patternId, long version,
                                   PatternStatus status) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        // Match the completion lock order: company before recurring pattern.
        companies.lockCompany(scope).orElseThrow(ApiException::notFound);
        if (status == PatternStatus.DETECTED) {
            throw ApiException.unprocessable("INVALID_DECISION", "Решение пользователя — CONFIRMED или EXCLUDED");
        }
        RecurringPattern current = patterns.lock(scope, accountId, patternId).orElseThrow(ApiException::notFound);
        if (current.version() != version) {
            throw ApiException.conflict("VERSION_CONFLICT", "Регулярность изменена; обновите данные");
        }
        Instant now = clock.instant();
        var next = new RecurringPattern(current.id(), current.companyId(), accountId, current.stableKey(),
                current.direction(), current.categoryCode(), current.counterpartyKey(), status, StatusSource.USER,
                current.schedule(), current.amount(), current.observations(), current.firstSeen(), current.lastSeen(),
                current.detectorVersion(), current.inputDataVersion(), current.knownAt(), now, version + 1);
        patterns.update(next, version);
        patterns.insertRevision(next, payload(next), userId);
        companies.incrementDataVersion(scope, now);
        return next;
    }

    public List<RecurringPattern> list(UUID userId, UUID companyId, UUID accountId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        return patterns.list(scope, accountId);
    }

    public String payload(RecurringPattern p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id().toString());
        m.put("accountId", p.accountId().toString());
        m.put("direction", p.direction().name());
        m.put("categoryCode", p.categoryCode());
        m.put("counterpartyKey", p.counterpartyKey());
        m.put("status", p.status().name());
        m.put("statusSource", p.statusSource().name());
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("frequency", p.schedule().frequency().name());
        schedule.put("weekday", p.schedule().weekday());
        schedule.put("dayOfMonth", p.schedule().dayOfMonth());
        schedule.put("businessDayRule", p.schedule().businessDayRule().name());
        m.put("schedule", schedule);
        m.put("amount", p.amount().toPlainString());
        m.put("observations", p.observations());
        m.put("firstSeen", p.firstSeen().toString());
        m.put("lastSeen", p.lastSeen().toString());
        m.put("inputDataVersion", p.inputDataVersion());
        m.put("detectorVersion", p.detectorVersion());
        m.put("version", p.version());
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record MergeResult(int created, int refreshed, int keptUserDecisions, int ambiguousSkipped) {
    }
}
