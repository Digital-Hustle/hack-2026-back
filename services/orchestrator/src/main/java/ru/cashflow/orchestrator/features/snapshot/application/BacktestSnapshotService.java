package ru.cashflow.orchestrator.features.snapshot.application;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.knowledge.KnowledgeReader;
import ru.cashflow.orchestrator.features.settings.model.ForecastSettings;
import ru.cashflow.orchestrator.features.snapshot.command.BacktestSnapshotException;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;
import ru.cashflow.orchestrator.features.snapshot.command.SnapshotAssembler;
import ru.cashflow.orchestrator.features.snapshot.command.SnapshotException;
import ru.cashflow.orchestrator.features.snapshot.model.BacktestOrigin;
import ru.cashflow.orchestrator.features.snapshot.model.BacktestOrigin.Status;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository.StoredSnapshot;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.BacktestRqW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.BacktestSnapshotW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.DailyActualW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.ForecastSnapshotW;

/**
 * Handoff §7 task 11: operational knownAt replay for a rolling-origin backtest. Input reconstruction uses each
 * origin's own exclusive end-of-day cutoff; scoring uses the outer cutoff and is never passed to the input builder.
 * The complete build is one MVCC view. A future job creator can build/store this inside its own REPEATABLE READ
 * transaction, then call Forecast only after commit. Nested risk calibration belongs to task 12.
 */
@Service
public class BacktestSnapshotService {

    private static final String PREPARATION_VERSION = "BACKTEST1";
    private static final int HORIZON_DAYS = 30;
    private static final int MIN_STEP_DAYS = 7;
    private static final int MAX_STEP_DAYS = 30;
    private static final int MAX_ORIGINS = 100;

    private final SnapshotService inputs;
    private final KnowledgeReader reader;
    private final CompanyRepository companies;
    private final SnapshotRepository snapshots;
    private final ObjectMapper json;
    private final Clock clock;

    public BacktestSnapshotService(SnapshotService inputs, KnowledgeReader reader, CompanyRepository companies,
                                   SnapshotRepository snapshots, ObjectMapper json, Clock clock) {
        this.inputs = inputs;
        this.reader = reader;
        this.companies = companies;
        this.snapshots = snapshots;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Built build(CompanyScope scope, UUID accountId, LocalDate outerAsOf, BacktestRqW request,
                       ForecastSettings experimentSettings) {
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        var company = companies.findCompany(scope).orElseThrow(ApiException::notFound);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(company.timezone())));
        if (outerAsOf == null || !outerAsOf.isBefore(today)) {
            throw new SnapshotException("OPEN_DAY", "Внешний cutoff должен быть закрытым днём компании");
        }
        Dates dates = validate(accountId, outerAsOf, request, experimentSettings);
        ForecastSettings fixedSettings = new ForecastSettings(experimentSettings.windowWeeks(), HORIZON_DAYS,
                experimentSettings.bufferAmount().setScale(2), experimentSettings.scenarioCount(),
                experimentSettings.minCalibrationOrigins(), experimentSettings.calibrationStepDays(),
                experimentSettings.algorithm());
        Instant scoringKnownBefore = KnowledgeReader.endOfDay(outerAsOf, company.timezone());
        Map<LocalDate, DailyActualW> scoring = scoring(scope, accountId, dates, outerAsOf, scoringKnownBefore,
                fixedSettings);
        List<ForecastSnapshotW> originSnapshots = new ArrayList<>();
        List<BacktestOrigin> outcomes = new ArrayList<>();
        List<Map<String, Object>> traces = new ArrayList<>();
        Map<LocalDate, DailyActualW> actualDays = new TreeMap<>();

        for (LocalDate origin = dates.first(); !origin.isAfter(dates.last()); origin = origin.plusDays(request.stepDays())) {
            SnapshotService.Built input = null;
            String reason = null;
            if (origin.plusDays(HORIZON_DAYS).isAfter(outerAsOf)) {
                reason = "IMMATURE_ORIGIN";
            } else {
                try {
                    input = inputs.buildHistorical(scope, accountId, origin, request.scope(), company.dataVersion(),
                            company.timezone(), fixedSettings);
                } catch (SnapshotException exception) {
                    reason = exception.code();
                }
                if (reason == null && !allWeekdaysKnown(input.snapshot())) {
                    reason = "INSUFFICIENT_HISTORY";
                }
                if (reason == null && !completeScoring(origin, scoring)) {
                    reason = "INCOMPLETE_SCORING";
                }
            }
            var outcome = new BacktestOrigin(origin, reason == null ? Status.INCLUDED : Status.SKIPPED, reason);
            outcomes.add(outcome);
            traces.add(trace(outcome, reason == null ? input : null, company.timezone()));
            if (reason == null) {
                originSnapshots.add(input.snapshot());
                for (int day = 1; day <= HORIZON_DAYS; day++) {
                    LocalDate date = origin.plusDays(day);
                    actualDays.put(date, scoring.get(date));
                }
            }
        }
        if (originSnapshots.isEmpty()) {
            throw new BacktestSnapshotException(outcomes);
        }
        var normalizedRequest = new BacktestRqW(accountId.toString(), dates.first().toString(), dates.last().toString(),
                request.stepDays(), request.scope(), request.windowWeeks(), false);
        var snapshot = new BacktestSnapshotW(UUID.randomUUID().toString(), scope.companyId().toString(),
                accountId.toString(), company.dataVersion(), normalizedRequest, SnapshotAssembler.settingsW(fixedSettings),
                List.copyOf(originSnapshots), List.copyOf(actualDays.values()));
        String fingerprint = Fingerprint.of(json.valueToTree(snapshot));
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("preparationVersion", PREPARATION_VERSION);
        provenance.put("replayMode", "HISTORICAL_KNOWN_AT");
        provenance.put("outerAsOf", outerAsOf.toString());
        provenance.put("scoringKnownBefore", scoringKnownBefore.toString());
        provenance.put("experimentSettings", snapshot.settings());
        provenance.put("origins", List.copyOf(traces));
        return new Built(snapshot, outerAsOf, fingerprint, write(provenance), List.copyOf(outcomes));
    }

    /** Persist once inside the future job transaction; inputs/provenance never change when financial data changes. */
    @Transactional
    public StoredSnapshot store(CompanyScope scope, Built built) {
        var snapshot = built.snapshot();
        if (!scope.companyId().toString().equals(snapshot.companyId())) {
            throw ApiException.notFound();
        }
        UUID accountId = UUID.fromString(snapshot.accountId());
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        if (!Fingerprint.of(json.valueToTree(snapshot)).equals(built.fingerprint())) {
            throw new SnapshotException("FINGERPRINT_MISMATCH", "Backtest snapshot изменился после построения");
        }
        var stored = new StoredSnapshot(UUID.fromString(snapshot.snapshotId()), accountId, "BACKTEST", built.asOf(),
                snapshot.inputDataVersion(), built.fingerprint(), write(snapshot), built.provenance(), clock.instant());
        snapshots.insert(scope, stored);
        return stored;
    }

    private Map<LocalDate, DailyActualW> scoring(CompanyScope scope, UUID accountId, Dates dates, LocalDate outerAsOf,
                                                Instant knownBefore, ForecastSettings settings) {
        Map<LocalDate, DailyActualW> result = new TreeMap<>();
        if (!dates.first().plusDays(HORIZON_DAYS).isAfter(outerAsOf)) {
            LocalDate to = dates.last().plusDays(HORIZON_DAYS);
            if (to.isAfter(outerAsOf)) {
                to = outerAsOf;
            }
            var state = reader.read(scope, accountId, dates.first().plusDays(1), to, knownBefore, settings);
            state.days().forEach(day -> result.put(day.date(), SnapshotAssembler.day(day)));
        }
        return result;
    }

    private static boolean completeScoring(LocalDate origin, Map<LocalDate, DailyActualW> scoring) {
        for (int offset = 1; offset <= HORIZON_DAYS; offset++) {
            DailyActualW day = scoring.get(origin.plusDays(offset));
            if (day == null || "MISSING".equals(day.status()) || day.balanceEod() == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean allWeekdaysKnown(ForecastSnapshotW input) {
        return input.dailyHistory().stream().filter(day -> !"MISSING".equals(day.status()))
                .map(day -> LocalDate.parse(day.date()).getDayOfWeek()).distinct().count() == DayOfWeek.values().length;
    }

    private Map<String, Object> trace(BacktestOrigin outcome, SnapshotService.Built input, String timezone) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asOfDate", outcome.asOfDate().toString());
        result.put("knownBefore", KnowledgeReader.endOfDay(outcome.asOfDate(), timezone).toString());
        result.put("status", outcome.status().name());
        result.put("reason", outcome.reason());
        if (input != null) {
            result.put("snapshotId", input.snapshot().snapshotId());
            result.put("fingerprint", input.snapshot().fingerprint());
            try {
                result.put("inputProvenance", json.readTree(input.provenance()));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Invalid input provenance", exception);
            }
        }
        return result;
    }

    private static Dates validate(UUID accountId, LocalDate outerAsOf, BacktestRqW request, ForecastSettings settings) {
        if (request == null || settings == null || !settings.valid() || request.windowWeeks() != settings.windowWeeks()
                || !accountId.toString().equalsIgnoreCase(request.accountId())
                || request.stepDays() < MIN_STEP_DAYS || request.stepDays() > MAX_STEP_DAYS
                || (!SnapshotAssembler.HISTORY_ONLY.equals(request.scope())
                && !SnapshotAssembler.WITH_KNOWN_PLANS.equals(request.scope()))) {
            throw new SnapshotException("INVALID_BACKTEST_REQUEST", "Некорректные параметры эксперимента backtest");
        }
        LocalDate first = date(request.firstOrigin());
        LocalDate last = date(request.lastOrigin());
        if (first.isAfter(last) || last.isAfter(outerAsOf)) {
            throw new SnapshotException("INVALID_BACKTEST_REQUEST", "Диапазон origins должен заканчиваться до внешнего cutoff");
        }
        if (ChronoUnit.DAYS.between(first, last) / request.stepDays() + 1 > MAX_ORIGINS) {
            throw new SnapshotException("INVALID_BACKTEST_REQUEST", "Forecast принимает не более 100 origins");
        }
        if (request.evaluateRisk()) {
            throw new SnapshotException("NESTED_CALIBRATION_REQUIRED", "Оценка риска требует nested calibration из задачи 12");
        }
        return new Dates(first, last);
    }

    private static LocalDate date(String value) {
        if (value != null) {
            try {
                LocalDate date = LocalDate.parse(value);
                if (date.getYear() >= 1 && date.getYear() <= 9999 && date.toString().equals(value)) {
                    return date;
                }
            } catch (DateTimeParseException exception) {
                // The external error never echoes an untrusted field value.
            }
        }
        throw new SnapshotException("INVALID_BACKTEST_REQUEST", "Дата origin должна иметь формат YYYY-MM-DD");
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize backtest snapshot", exception);
        }
    }

    private record Dates(LocalDate first, LocalDate last) {
    }

    public record Built(BacktestSnapshotW snapshot, LocalDate asOf, String fingerprint, String provenance,
                        List<BacktestOrigin> origins) {
    }
}
