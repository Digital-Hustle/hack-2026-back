package ru.cashflow.orchestrator.features.snapshot.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.model.Company;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.knowledge.KnowledgeReader;
import ru.cashflow.orchestrator.features.knowledge.KnowledgeState;
import ru.cashflow.orchestrator.features.ledger.command.CounterpartyKeys;
import ru.cashflow.orchestrator.features.settings.model.ForecastSettings;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;
import ru.cashflow.orchestrator.features.snapshot.command.SnapshotAssembler;
import ru.cashflow.orchestrator.features.snapshot.command.SnapshotException;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository.StoredSnapshot;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.CalibrationOriginW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.ForecastSnapshotW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.RecurringDetectionInputW;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the current ForecastSnapshot (handoff §5 steps 1–11). The caller runs it in a REPEATABLE
 * READ transaction so the data version, facts, plans and patterns come from one MVCC view; no
 * network call happens inside it.
 */
@Service
public class SnapshotService {

    public static final String PREPARATION_VERSION = "SNAP2_KNOWLEDGE_CUTOFF";

    private final KnowledgeReader reader;
    private final CompanyRepository companies;
    private final SnapshotRepository snapshots;
    private final ObjectMapper json;
    private final Clock clock;

    public SnapshotService(
            KnowledgeReader reader,
            CompanyRepository companies,
            SnapshotRepository snapshots,
            ObjectMapper json,
            Clock clock) {
        this.reader = reader;
        this.companies = companies;
        this.snapshots = snapshots;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param asOf last closed business day of the forecast; must be before today in the company
     *     timezone, because an unfinished day is never declared complete and its EOD is not
     *     invented (§3)
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Built buildCurrent(
            CompanyScope scope,
            UUID accountId,
            LocalDate asOf,
            String scopeName,
            List<CalibrationOriginW> calibrationOrigins) {
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        Company company = companies.findCompany(scope).orElseThrow(ApiException::notFound);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(company.timezone())));
        if (!asOf.isBefore(today)) {
            throw ApiException.unprocessable(
                    "OPEN_DAY", "asOfDate должен быть последним закрытым днём");
        }
        long dataVersion = company.dataVersion();
        Instant knownBefore = clock.instant();
        return build(
                scope,
                accountId,
                asOf,
                scopeName,
                calibrationOrigins,
                dataVersion,
                knownBefore,
                "CURRENT",
                null);
    }

    /**
     * Snapshot for a historical cutoff T: knowledge is limited to the end of day T in the company
     * timezone, so late categories, plans, confirmations and facts are absent (§3). Used for
     * backtest and calibration origin inputs.
     */
    public Built buildHistorical(
            CompanyScope scope,
            UUID accountId,
            LocalDate asOf,
            String scopeName,
            long dataVersion,
            String timezone) {
        return buildHistorical(scope, accountId, asOf, scopeName, dataVersion, timezone, null);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public DetectionBuilt buildRecurringDetection(
            CompanyScope scope, UUID accountId, LocalDate asOf) {
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        var company = companies.findCompany(scope).orElseThrow(ApiException::notFound);
        if (!asOf.isBefore(LocalDate.now(clock.withZone(ZoneId.of(company.timezone()))))) {
            throw new SnapshotException("OPEN_DAY", "Дата detection должна быть закрытым днём");
        }
        var state =
                reader.read(
                        scope,
                        accountId,
                        asOf.minusDays(3660),
                        asOf,
                        clock.instant(),
                        ForecastSettings.defaults());
        if (state.payments().size() > 250_000) {
            throw new SnapshotException("TOO_MANY_PAYMENTS",
                    "История для поиска регулярностей превышает лимит 250000 операций");
        }
        var input =
                new RecurringDetectionInputW(
                        accountId.toString(),
                        asOf.toString(),
                        company.dataVersion(),
                        state.payments().stream()
                                .sorted(java.util.Comparator.comparing(p -> p.id().toString()))
                                .map(SnapshotAssembler::payment)
                                .toList());
        String payload = write(input);
        return new DetectionBuilt(
                UUID.randomUUID(),
                accountId,
                asOf,
                company.dataVersion(),
                Fingerprint.of(json.valueToTree(input)),
                payload,
                provenance(state, clock.instant(), "CURRENT_DETECTION", 0, true));
    }

    /**
     * Explicit fixed settings for a backtest experiment, independent of historical/current account
     * settings.
     */
    public Built buildHistorical(
            CompanyScope scope,
            UUID accountId,
            LocalDate asOf,
            String scopeName,
            long dataVersion,
            String timezone,
            ForecastSettings experimentSettings) {
        Instant knownBefore = KnowledgeReader.endOfDay(asOf, timezone);
        return build(
                scope,
                accountId,
                asOf,
                scopeName,
                List.of(),
                dataVersion,
                knownBefore,
                "HISTORICAL_KNOWN_AT",
                experimentSettings);
    }

    private Built build(
            CompanyScope scope,
            UUID accountId,
            LocalDate asOf,
            String scopeName,
            List<CalibrationOriginW> calibrationOrigins,
            long dataVersion,
            Instant knownBefore,
            String replayMode,
            ForecastSettings experimentSettings) {
        ForecastSettings settings =
                experimentSettings == null
                        ? reader.read(scope, accountId, asOf, asOf, knownBefore).settings()
                        : experimentSettings;
        LocalDate historyFrom = SnapshotAssembler.windowStart(asOf, settings.windowWeeks());
        KnowledgeState state =
                reader.read(scope, accountId, historyFrom, asOf, knownBefore, experimentSettings);
        LocalDate firstKnown =
                state.days().stream()
                        .filter(
                                d ->
                                        d.status()
                                                != ru.cashflow.orchestrator.features.ledger.model
                                                        .LedgerTypes.DayStatus.MISSING)
                        .map(d -> d.date())
                        .min(LocalDate::compareTo)
                        .orElse(null);
        if (firstKnown == null) {
            throw new SnapshotException(
                    "INSUFFICIENT_HISTORY", "Нет ни одного полного дня истории в окне");
        }
        if (firstKnown.isAfter(historyFrom)) {
            historyFrom = firstKnown;
            state =
                    reader.read(
                            scope, accountId, historyFrom, asOf, knownBefore, experimentSettings);
        }
        UUID snapshotId = UUID.randomUUID();
        String timezone =
                companies.findCompany(scope).orElseThrow(ApiException::notFound).timezone();
        ForecastSnapshotW snapshot =
                SnapshotAssembler.assemble(
                        snapshotId,
                        state,
                        scopeName,
                        dataVersion,
                        historyFrom,
                        calibrationOrigins,
                        json,
                        timezone);
        return new Built(
                snapshot,
                provenance(
                        state,
                        knownBefore,
                        replayMode,
                        calibrationOrigins.size(),
                        experimentSettings != null));
    }

    /** Persists an immutable snapshot inside the caller's transaction. */
    public StoredSnapshot store(CompanyScope scope, String kind, Built built) {
        ForecastSnapshotW s = built.snapshot();
        var stored =
                new StoredSnapshot(
                        UUID.fromString(s.snapshotId()),
                        UUID.fromString(s.accountId()),
                        kind,
                        LocalDate.parse(s.asOfDate()),
                        s.inputDataVersion(),
                        s.fingerprint(),
                        write(s),
                        built.provenance(),
                        clock.instant());
        snapshots.insert(scope, stored);
        return stored;
    }

    private String provenance(
            KnowledgeState state,
            Instant knownBefore,
            String replayMode,
            int origins,
            boolean experimentSettings) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("preparationVersion", PREPARATION_VERSION);
        m.put("counterpartyKeyVersion", CounterpartyKeys.VERSION);
        m.put("replayMode", replayMode);
        m.put("knownBefore", knownBefore.toString());
        m.put("planRevisions", state.revisionsUsed().stream().map(UUID::toString).toList());
        m.put(
                "paymentCategoryVersions",
                state.payments().stream()
                        .map(p -> Map.of("paymentId", p.id().toString(), "version", p.version()))
                        .toList());
        m.put(
                "planVersions",
                state.plans().stream()
                        .map(p -> Map.of("planId", p.id().toString(), "version", p.version()))
                        .toList());
        m.put(
                "recurringVersions",
                state.recurring().stream()
                        .map(p -> Map.of("patternId", p.id().toString(), "version", p.version()))
                        .toList());
        m.put(
                "knownCompletenessIds",
                state.completeness().stream().map(r -> r.id().toString()).sorted().toList());
        m.put(
                "knownAnchorIds",
                state.anchors().stream().map(a -> a.id().toString()).sorted().toList());
        m.put("settingsSource", experimentSettings ? "EXPERIMENT" : "ACCOUNT_KNOWN_AT");
        m.put("calibrationOrigins", origins);
        m.put(
                "startBalanceAnchorDate",
                state.startBalance().map(b -> b.anchorDate().toString()).orElse(null));
        return write(m);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Built(ForecastSnapshotW snapshot, String provenance) {}

    public record DetectionBuilt(
            UUID id,
            UUID accountId,
            LocalDate asOf,
            long inputVersion,
            String fingerprint,
            String payload,
            String provenance) {}
}
