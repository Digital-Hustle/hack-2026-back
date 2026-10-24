package ru.cashflow.orchestrator.features.snapshot.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import ru.cashflow.orchestrator.features.snapshot.command.SnapshotAssembler;
import ru.cashflow.orchestrator.features.snapshot.command.SnapshotException;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository.StoredSnapshot;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.CalibrationOriginW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.ForecastSnapshotW;

/**
 * Builds the current ForecastSnapshot (handoff §5 steps 1–11). The caller runs it in a REPEATABLE READ transaction so
 * the data version, facts, plans and patterns come from one MVCC view; no network call happens inside it.
 */
@Service
public class SnapshotService {

    public static final String PREPARATION_VERSION = "SNAP1";

    private final KnowledgeReader reader;
    private final CompanyRepository companies;
    private final SnapshotRepository snapshots;
    private final ObjectMapper json;
    private final Clock clock;

    public SnapshotService(KnowledgeReader reader, CompanyRepository companies, SnapshotRepository snapshots,
                           ObjectMapper json, Clock clock) {
        this.reader = reader;
        this.companies = companies;
        this.snapshots = snapshots;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param asOf last closed business day of the forecast; must be before today in the company timezone, because an
     *             unfinished day is never declared complete and its EOD is not invented (§3)
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Built buildCurrent(CompanyScope scope, UUID accountId, LocalDate asOf, String scopeName,
                              List<CalibrationOriginW> calibrationOrigins) {
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        Company company = companies.findCompany(scope).orElseThrow(ApiException::notFound);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(company.timezone())));
        if (!asOf.isBefore(today)) {
            throw ApiException.unprocessable("OPEN_DAY", "asOfDate должен быть последним закрытым днём");
        }
        long dataVersion = company.dataVersion();
        Instant knownBefore = clock.instant();
        return build(scope, accountId, asOf, scopeName, calibrationOrigins, dataVersion, knownBefore, "CURRENT");
    }

    /**
     * Snapshot for a historical cutoff T: knowledge is limited to the end of day T in the company timezone, so late
     * categories, plans, confirmations and facts are absent (§3). Used for backtest and calibration origin inputs.
     */
    public Built buildHistorical(CompanyScope scope, UUID accountId, LocalDate asOf, String scopeName,
                                 long dataVersion, String timezone) {
        Instant knownBefore = KnowledgeReader.endOfDay(asOf, timezone);
        return build(scope, accountId, asOf, scopeName, List.of(), dataVersion, knownBefore, "HISTORICAL_KNOWN_AT");
    }

    private Built build(CompanyScope scope, UUID accountId, LocalDate asOf, String scopeName,
                        List<CalibrationOriginW> calibrationOrigins, long dataVersion, Instant knownBefore,
                        String replayMode) {
        KnowledgeState probe = reader.read(scope, accountId, asOf, asOf, knownBefore);
        LocalDate historyFrom = SnapshotAssembler.windowStart(asOf, probe.settings().windowWeeks());
        KnowledgeState state = reader.read(scope, accountId, historyFrom, asOf, knownBefore);
        LocalDate firstKnown = state.days().stream()
                .filter(d -> d.status() != ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.DayStatus.MISSING)
                .map(d -> d.date()).min(LocalDate::compareTo).orElse(null);
        if (firstKnown == null) {
            throw new SnapshotException("INSUFFICIENT_HISTORY", "Нет ни одного полного дня истории в окне");
        }
        if (firstKnown.isAfter(historyFrom)) {
            historyFrom = firstKnown;
            state = reader.read(scope, accountId, historyFrom, asOf, knownBefore);
        }
        UUID snapshotId = UUID.randomUUID();
        ForecastSnapshotW snapshot = SnapshotAssembler.assemble(snapshotId, state, scopeName, dataVersion, historyFrom,
                calibrationOrigins, json);
        return new Built(snapshot, provenance(state, knownBefore, replayMode, calibrationOrigins.size()));
    }

    /** Persists an immutable snapshot inside the caller's transaction. */
    public StoredSnapshot store(CompanyScope scope, String kind, Built built) {
        ForecastSnapshotW s = built.snapshot();
        var stored = new StoredSnapshot(UUID.fromString(s.snapshotId()), UUID.fromString(s.accountId()), kind,
                LocalDate.parse(s.asOfDate()), s.inputDataVersion(), s.fingerprint(), write(s), built.provenance(),
                clock.instant());
        snapshots.insert(scope, stored);
        return stored;
    }

    private String provenance(KnowledgeState state, Instant knownBefore, String replayMode, int origins) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("preparationVersion", PREPARATION_VERSION);
        m.put("counterpartyKeyVersion", CounterpartyKeys.VERSION);
        m.put("replayMode", replayMode);
        m.put("knownBefore", knownBefore.toString());
        m.put("planRevisions", state.revisionsUsed().stream().map(UUID::toString).toList());
        m.put("calibrationOrigins", origins);
        m.put("startBalanceAnchorDate", state.startBalance().map(b -> b.anchorDate().toString()).orElse(null));
        return write(m);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Built(ForecastSnapshotW snapshot, String provenance) {
    }
}
