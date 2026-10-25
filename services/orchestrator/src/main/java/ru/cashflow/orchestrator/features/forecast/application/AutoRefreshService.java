package ru.cashflow.orchestrator.features.forecast.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.forecast.repository.AutoRefreshRepository;
import ru.cashflow.orchestrator.features.jobs.application.JobService;
import ru.cashflow.orchestrator.features.jobs.repository.JobRepository;

/**
 * Reconciles financial input versions with the latest saved forecast. A scheduled tick is intentionally preferred
 * over hooks in every write service: it also covers manual payments, imports, plan changes and future data sources
 * without creating a transaction-boundary dependency. One account never blocks another.
 */
@Service
public class AutoRefreshService {

    private static final Logger LOG = LoggerFactory.getLogger(AutoRefreshService.class);

    private final AutoRefreshRepository candidates;
    private final CompanyAccess access;
    private final JobService jobs;
    private final JobRepository jobRepository;
    private final Clock clock;
    private final ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository ledger;
    private final int batchSize;
    private final Duration retryDelay;

    public AutoRefreshService(AutoRefreshRepository candidates, CompanyAccess access, JobService jobs,
                              JobRepository jobRepository, Clock clock,
                              ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository ledger,
                              @Value("${orchestrator.auto-refresh.batch-size:5}") int batchSize,
                              @Value("${orchestrator.auto-refresh.retry-delay:5m}") Duration retryDelay) {
        this.candidates = candidates;
        this.access = access;
        this.jobs = jobs;
        this.jobRepository = jobRepository;
        this.clock = clock;
        this.ledger = ledger;
        this.batchSize = batchSize;
        if (retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException("auto-refresh.retry-delay must be positive");
        }
        this.retryDelay = retryDelay;
    }

    public int reconcile() {
        Instant now = clock.instant();
        List<AutoRefreshRepository.Candidate> pending = candidates.candidates(Math.max(1, batchSize), now);
        int created = 0;
        for (AutoRefreshRepository.Candidate candidate : pending) {
            try {
                CompanyScope scope = access.systemScope(candidate.companyId());
                if (!candidates.reserve(candidate, now, now.plus(retryDelay))) {
                    continue;
                }
                if (candidate.activeJobId() != null) {
                    if (isSupersededAuto(candidate)) {
                        if (!jobRepository.cancel(scope, candidate.activeJobId(), clock.instant())) {
                            continue;
                        }
                    } else {
                        continue;
                    }
                }
                LocalDate yesterday = LocalDate.now(clock.withZone(ZoneId.of(candidate.timezone()))).minusDays(1);
                // History loaded from an archive may end before yesterday; forecasting from yesterday would fail
                // with NO_START_BALANCE on every retry, so the last known day of history is used instead.
                LocalDate asOf = ledger.lastKnownDay(scope, candidate.accountId(), yesterday).orElse(yesterday);
                String scopeName = candidate.latestScope() == null ? "WITH_KNOWN_PLANS" : candidate.latestScope();
                jobs.createAutomaticForecast(scope, candidate.accountId(), asOf, scopeName, candidate.dataVersion(),
                        key(candidate.accountId(), candidate.dataVersion(), asOf));
                created++;
            } catch (RuntimeException exception) {
                // A single account with insufficient history or a transient race must not stop the scheduler.
                LOG.warn("auto_refresh_skipped companyId={} accountId={} reason={}", candidate.companyId(),
                        candidate.accountId(), exception.getClass().getSimpleName());
            }
        }
        return created;
    }

    private static boolean isSupersededAuto(AutoRefreshRepository.Candidate candidate) {
        return "QUEUED".equals(candidate.activeJobStatus())
                && "AUTO".equals(candidate.activeJobOrigin())
                && candidate.activeJobVersion() != null
                && candidate.activeJobVersion() < candidate.dataVersion();
    }

    private static String key(UUID accountId, long dataVersion, LocalDate asOf) {
        return "auto-" + accountId + "-" + dataVersion + "-" + asOf;
    }
}
