package ru.cashflow.orchestrator.features.snapshot.application;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.knowledge.KnowledgeReader;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategoryRevision;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.features.settings.model.ForecastSettings;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;
import ru.cashflow.orchestrator.features.snapshot.command.SnapshotAssembler;
import ru.cashflow.orchestrator.features.snapshot.command.SnapshotException;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.CalibrationOriginW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.DailyActualW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.ForecastSnapshotW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.SnapshotPaymentW;

/**
 * Handoff §7 task 12. Called inside the same REPEATABLE READ transaction that built the outer input.
 * Only mature 30-day outcomes known at that outer cutoff are used. Nested inputs are always point-only.
 */
@Service
public class CalibrationOriginBuilder {

    private static final int HORIZON_DAYS = 30;
    private static final int MAX_CANDIDATES = 100;
    private static final String PREPARATION_VERSION = "CAL1";
    private static final String CATEGORY_POLICY = "ORIGIN_KNOWN_OR_INITIAL_KNOWN_RULE_VERSION_V1";

    private final SnapshotService inputs;
    private final KnowledgeReader reader;
    private final LedgerRepository ledger;
    private final CompanyRepository companies;
    private final ObjectMapper json;

    public CalibrationOriginBuilder(SnapshotService inputs, KnowledgeReader reader, LedgerRepository ledger,
                                    CompanyRepository companies, ObjectMapper json) {
        this.inputs = inputs;
        this.reader = reader;
        this.ledger = ledger;
        this.companies = companies;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SnapshotService.Built enrich(CompanyScope scope, SnapshotService.Built built) {
        ForecastSnapshotW outer = built.snapshot();
        if (!scope.companyId().toString().equals(outer.companyId())) {
            throw ApiException.notFound();
        }
        UUID accountId = UUID.fromString(outer.accountId());
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        var company = companies.findCompany(scope).orElseThrow(ApiException::notFound);
        if (company.dataVersion() != outer.inputDataVersion() || !outer.calibrationOrigins().isEmpty()) {
            throw new SnapshotException("INVALID_CALIBRATION_INPUT", "Нужен новый point-only snapshot той же версии данных");
        }
        var s = outer.settings();
        var settings = new ForecastSettings(s.windowWeeks(), s.horizonDays(), new BigDecimal(s.bufferAmount()),
                s.scenarioCount(), s.minCalibrationOrigins(), s.calibrationStepDays(), s.algorithm());
        if (!settings.valid()) {
            throw new SnapshotException("INVALID_CALIBRATION_INPUT", "Некорректные фиксированные параметры calibration");
        }
        ObjectNode provenance = (ObjectNode) tree(built.provenance());
        Instant scoringKnownBefore = Instant.parse(provenance.path("knownBefore").asText());
        LocalDate asOf = LocalDate.parse(outer.asOfDate());
        LocalDate latest = asOf.minusDays(HORIZON_DAYS);
        LocalDate from = latest.minusDays((long) (MAX_CANDIDATES - 1) * settings.calibrationStepDays()).plusDays(1);
        var scoring = reader.read(scope, accountId, from, asOf, scoringKnownBefore, settings);
        Map<LocalDate, DailyActualW> days = new HashMap<>();
        scoring.days().forEach(day -> days.put(day.date(), SnapshotAssembler.day(day)));
        Map<UUID, CategoryRevision> initial = new HashMap<>();
        ledger.initialRevisionsKnownBefore(scope, accountId, from, asOf, scoringKnownBefore)
                .forEach(r -> initial.put(r.paymentId(), r));
        List<CalibrationOriginW> included = new ArrayList<>();
        List<Map<String, Object>> traces = new ArrayList<>();
        for (int index = 0; index < MAX_CANDIDATES && included.size() < settings.minCalibrationOrigins(); index++) {
            LocalDate origin = latest.minusDays((long) index * settings.calibrationStepDays());
            SnapshotService.Built input = null;
            String reason = null;
            try {
                input = inputs.buildHistorical(scope, accountId, origin, outer.scope(), outer.inputDataVersion(),
                        company.timezone(), settings);
            } catch (SnapshotException exception) {
                reason = exception.code();
            }
            if (reason == null && input.snapshot().dailyHistory().stream().filter(d -> !"MISSING".equals(d.status()))
                    .map(d -> LocalDate.parse(d.date()).getDayOfWeek()).distinct().count() != DayOfWeek.values().length) {
                reason = "INSUFFICIENT_HISTORY";
            }
            List<DailyActualW> actualDays = new ArrayList<>();
            for (int day = 1; day <= HORIZON_DAYS; day++) {
                DailyActualW actual = days.get(origin.plusDays(day));
                if (actual != null && !"MISSING".equals(actual.status())) {
                    actualDays.add(actual);
                }
            }
            if (reason == null && actualDays.size() != HORIZON_DAYS) {
                reason = "INCOMPLETE_SCORING";
            }
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("asOfDate", origin.toString());
            Instant originKnownBefore = KnowledgeReader.endOfDay(origin, company.timezone());
            trace.put("knownBefore", originKnownBefore.toString());
            if (reason == null) {
                List<Payment> actual = scoring.payments().stream().filter(p -> p.operationDate().isAfter(origin)
                        && !p.operationDate().isAfter(origin.plusDays(HORIZON_DAYS))).toList();
                Map<UUID, CategoryRevision> known = new HashMap<>();
                ledger.revisionsKnownBefore(scope, accountId, origin.plusDays(1), origin.plusDays(HORIZON_DAYS),
                        originKnownBefore).forEach(r -> known.put(r.paymentId(), r));
                List<String> rules = ledger.ruleVersionsKnownBefore(scope, accountId, origin, originKnownBefore);
                List<SnapshotPaymentW> payments = new ArrayList<>();
                List<Map<String, Object>> categories = new ArrayList<>();
                for (Payment payment : actual) {
                    CategoryRevision category = known.get(payment.id());
                    if (category == null) {
                        category = initial.get(payment.id());
                        if (category == null || category.categorySource() == CategorySource.USER
                                || !rules.contains(category.ruleVersion())) {
                            reason = "SCORING_RULES_UNAVAILABLE";
                            break;
                        }
                    }
                    payments.add(new SnapshotPaymentW(payment.id().toString(), payment.operationDate().toString(),
                            SnapshotAssembler.money(payment.amount()), payment.direction().name(), category.categoryCode(),
                            payment.counterpartyKey()));
                    categories.add(Map.of("paymentId", payment.id().toString(), "version", category.version()));
                }
                if (reason == null) {
                    payments.sort(Comparator.comparing(SnapshotPaymentW::date).thenComparing(SnapshotPaymentW::paymentId));
                    included.add(new CalibrationOriginW(input.snapshot(), List.copyOf(actualDays), List.copyOf(payments)));
                    trace.put("inputProvenance", tree(input.provenance()));
                    trace.put("inputFingerprint", input.snapshot().fingerprint());
                    trace.put("scoringCategoryVersions", categories);
                    trace.put("availableRuleVersions", rules);
                }
            }
            trace.put("status", reason == null ? "INCLUDED" : "SKIPPED");
            trace.put("reason", reason);
            traces.add(trace);
        }
        included.sort(Comparator.comparing(c -> c.input().asOfDate()));
        traces.sort(Comparator.comparing(t -> (String) t.get("asOfDate")));
        Map<String, Object> calibration = new LinkedHashMap<>();
        calibration.put("preparationVersion", PREPARATION_VERSION);
        calibration.put("selectionPolicy", "LATEST_MATURE_GRID_UNTIL_MINIMUM_V1");
        calibration.put("latestCandidate", latest.toString());
        calibration.put("stepDays", settings.calibrationStepDays());
        calibration.put("maxCandidates", MAX_CANDIDATES);
        calibration.put("targetCount", settings.minCalibrationOrigins());
        calibration.put("includedCount", included.size());
        calibration.put("scoringKnownBefore", scoringKnownBefore.toString());
        calibration.put("scoringCategoryPolicy", CATEGORY_POLICY);
        calibration.put("origins", traces);
        provenance.put("calibrationOrigins", included.size());
        provenance.set("calibration", json.valueToTree(calibration));
        return new SnapshotService.Built(attach(outer, List.copyOf(included)), write(provenance));
    }

    private ForecastSnapshotW attach(ForecastSnapshotW outer, List<CalibrationOriginW> origins) {
        ObjectNode payload = json.valueToTree(outer);
        payload.set("calibrationOrigins", json.valueToTree(origins));
        payload.put("fingerprint", Fingerprint.of(payload));
        try {
            return json.treeToValue(payload, ForecastSnapshotW.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid calibration snapshot", exception);
        }
    }

    private JsonNode tree(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid snapshot provenance", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize calibration provenance", exception);
        }
    }
}
