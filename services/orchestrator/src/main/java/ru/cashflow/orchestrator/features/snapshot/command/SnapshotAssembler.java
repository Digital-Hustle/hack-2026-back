package ru.cashflow.orchestrator.features.snapshot.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ru.cashflow.orchestrator.features.knowledge.KnowledgeState;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.DailyObservation;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ExpectedPayment;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanStatus;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.PatternStatus;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.RecurringPattern;
import ru.cashflow.orchestrator.features.settings.model.ForecastSettings;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.CalibrationOriginW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.CompletenessRangeW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.DailyActualW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.ExpectedPaymentW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.ForecastSettingsW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.ForecastSnapshotW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.RecurringPatternW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.ScheduleW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.SnapshotPaymentW;
import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire.SourceDocumentW;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Pure translation of a KnowledgeState into the ForecastSnapshot wire format with the canonical
 * ordering of §5. Applies §4.1 rules: only CONFIRMED patterns, HISTORY_ONLY → no plans,
 * WITH_KNOWN_PLANS → only open/partially settled plans with remaining > 0. It never adds days,
 * payments or plans that the state does not contain.
 */
public final class SnapshotAssembler {

    public static final String ALGORITHM_VERSION = ForecastSettings.ALGORITHM;
    public static final String HISTORY_ONLY = "HISTORY_ONLY";
    public static final String WITH_KNOWN_PLANS = "WITH_KNOWN_PLANS";
    private static final DateTimeFormatter INSTANT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
                    .withZone(java.time.ZoneOffset.UTC);

    private SnapshotAssembler() {}

    public static ForecastSnapshotW assemble(
            UUID snapshotId,
            KnowledgeState state,
            String scope,
            long inputDataVersion,
            LocalDate historyFrom,
            List<CalibrationOriginW> calibrationOrigins,
            ObjectMapper json,
            String timezone) {
        if (!HISTORY_ONLY.equals(scope) && !WITH_KNOWN_PLANS.equals(scope)) {
            throw new IllegalArgumentException("unknown scope");
        }
        var start =
                state.startBalance()
                        .orElseThrow(
                                () ->
                                        new SnapshotException(
                                                "NO_START_BALANCE",
                                                "Нет подтверждённого остатка на дату прогноза без"
                                                    + " неизвестного периода"));
        ForecastSettings s = state.settings();
        var settings = settingsW(s);
        List<DailyActualW> days =
                state.days().stream()
                        .filter(d -> !d.date().isBefore(historyFrom))
                        .sorted(Comparator.comparing(DailyObservation::date))
                        .map(SnapshotAssembler::day)
                        .toList();
        requireContinuous(days, historyFrom, state.asOf());
        List<SnapshotPaymentW> payments =
                state.payments().stream()
                        .filter(
                                p ->
                                        !p.operationDate().isBefore(historyFrom)
                                                && !p.operationDate().isAfter(state.asOf()))
                        .sorted(
                                Comparator.comparing(Payment::operationDate)
                                        .thenComparing(p -> p.id().toString()))
                        .map(SnapshotAssembler::payment)
                        .toList();
        List<ExpectedPaymentW> plans =
                HISTORY_ONLY.equals(scope)
                        ? List.of()
                        : state.plans().stream()
                                .filter(
                                        p ->
                                                p.status() == PlanStatus.OPEN
                                                        || p.status()
                                                                == PlanStatus.PARTIALLY_SETTLED)
                                .filter(p -> p.remainingAmount().signum() > 0)
                                .sorted(
                                        Comparator.comparing(ExpectedPayment::dueDate)
                                                .thenComparing(p -> p.id().toString()))
                                .map(p -> plan(p, state.asOf()))
                                .toList();
        List<RecurringPatternW> patterns =
                state.recurring().stream()
                        .filter(p -> p.status() == PatternStatus.CONFIRMED)
                        .sorted(Comparator.comparing(p -> p.id().toString()))
                        .map(SnapshotAssembler::pattern)
                        .toList();
        List<CompletenessRangeW> ranges =
                resolvedRanges(state.completeness(), state.accountId(), historyFrom, state.asOf());
        List<CalibrationOriginW> origins =
                calibrationOrigins.stream()
                        .sorted(Comparator.comparing(o -> o.input().asOfDate()))
                        .toList();
        Set<String> cutoffs = new HashSet<>();
        origins.forEach(
                o -> {
                    if (!cutoffs.add(o.input().asOfDate())) {
                        throw new SnapshotException(
                                "DUPLICATE_ORIGIN", "Повторяющийся calibration origin");
                    }
                });
        var draft =
                new ForecastSnapshotW(
                        snapshotId.toString(),
                        state.companyId().toString(),
                        state.accountId().toString(),
                        state.asOf().toString(),
                        state.knownBefore().toString(),
                        timezone,
                        inputDataVersion,
                        ALGORITHM_VERSION,
                        settings,
                        scope,
                        money(start.balance()),
                        start.anchorDate().toString(),
                        historyFrom.toString(),
                        days,
                        payments,
                        plans,
                        patterns,
                        ranges,
                        origins,
                        null);
        String fingerprint = Fingerprint.of(json.valueToTree(draft));
        return new ForecastSnapshotW(
                draft.snapshotId(),
                draft.companyId(),
                draft.accountId(),
                draft.asOfDate(),
                draft.knowledgeCutoff(),
                draft.timezone(),
                draft.inputDataVersion(),
                draft.algorithmVersion(),
                draft.settings(),
                draft.scope(),
                draft.startBalance(),
                draft.startBalanceDate(),
                draft.historyFrom(),
                draft.dailyHistory(),
                draft.payments(),
                draft.knownPlans(),
                draft.recurringPatterns(),
                draft.completeRanges(),
                draft.calibrationOrigins(),
                fingerprint);
    }

    /**
     * First day of the train window [T - windowWeeks*7 + 1, T], clamped to the first known day
     * (§4.1).
     */
    public static LocalDate windowStart(LocalDate asOf, int windowWeeks) {
        return asOf.minusDays((long) windowWeeks * 7 - 1);
    }

    public static ForecastSettingsW settingsW(ForecastSettings s) {
        return new ForecastSettingsW(
                s.windowWeeks(),
                s.horizonDays(),
                money(s.bufferAmount()),
                s.scenarioCount(),
                s.minCalibrationOrigins(),
                s.calibrationStepDays(),
                s.algorithm());
    }

    public static DailyActualW day(DailyObservation d) {
        return new DailyActualW(
                d.date().toString(),
                money(d.inflow()),
                money(d.outflow()),
                money(d.balanceEod()),
                d.status().name(),
                d.paymentCount(),
                d.balanceMismatch());
    }

    public static SnapshotPaymentW payment(Payment p) {
        return new SnapshotPaymentW(
                p.id().toString(),
                p.operationDate().toString(),
                money(p.amount()),
                p.direction().name(),
                p.categoryCode(),
                p.counterpartyKey());
    }

    public static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    public static String instant(Instant value) {
        return INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * Completeness passed to Forecast must not contradict dailyHistory (§4): each returned range
     * covers a maximal run of complete days inside the window, built from the already resolved day
     * statuses.
     */
    private static List<CompletenessRangeW> resolvedRanges(
            List<CompletenessRange> ranges, UUID accountId, LocalDate from, LocalDate to) {
        List<CompletenessRange> ordered =
                ranges.stream()
                        .sorted(
                                Comparator.comparing(CompletenessRange::knownAt)
                                        .thenComparing(r -> r.id().toString()))
                        .toList();
        List<CompletenessRangeW> result = new ArrayList<>();
        LocalDate runStart = null;
        CompletenessRange source = null;
        for (LocalDate d = from; !d.isAfter(to.plusDays(1)); d = d.plusDays(1)) {
            CompletenessRange effective = null;
            for (CompletenessRange r : ordered) {
                if (!d.isBefore(r.fromDate()) && !d.isAfter(r.toDate())) {
                    effective = r;
                }
            }
            boolean complete =
                    !d.isAfter(to)
                            && effective != null
                            && effective.completeness() == Completeness.CONFIRMED_COMPLETE;
            if (complete && runStart == null) {
                runStart = d;
                source = effective;
            } else if (!complete && runStart != null) {
                result.add(
                        new CompletenessRangeW(
                                source.id().toString(),
                                accountId.toString(),
                                runStart.toString(),
                                d.minusDays(1).toString(),
                                "CONFIRMED_COMPLETE",
                                source.reason(),
                                instant(source.knownAt())));
                runStart = null;
            }
        }
        result.sort(
                Comparator.comparing(CompletenessRangeW::fromDate)
                        .thenComparing(CompletenessRangeW::toDate)
                        .thenComparing(CompletenessRangeW::id));
        return result;
    }

    private static void requireContinuous(List<DailyActualW> days, LocalDate from, LocalDate to) {
        long expected = ChronoUnit.DAYS.between(from, to) + 1;
        if (days.size() != expected) {
            throw new SnapshotException(
                    "HISTORY_NOT_CONTINUOUS", "Дневная история должна покрывать каждый день");
        }
        LocalDate d = from;
        for (DailyActualW day : days) {
            if (!day.date().equals(d.toString())) {
                throw new SnapshotException(
                        "HISTORY_NOT_CONTINUOUS", "Дневная история должна покрывать каждый день");
            }
            d = d.plusDays(1);
        }
    }

    private static ExpectedPaymentW plan(ExpectedPayment p, LocalDate asOf) {
        var d = p.sourceDocument();
        return new ExpectedPaymentW(
                p.id().toString(),
                p.companyId().toString(),
                p.accountId().toString(),
                p.title(),
                p.direction().name(),
                money(p.amount()),
                p.dueDate().toString(),
                p.categoryCode(),
                p.counterpartyKey(),
                p.note(),
                p.relation().name(),
                p.recurringPatternId() == null ? null : p.recurringPatternId().toString(),
                p.baselineCounterpartyKey(),
                p.source().name(),
                d == null
                        ? null
                        : new SourceDocumentW(
                                d.externalSystem(),
                                d.externalId(),
                                d.documentNumber(),
                                d.issuedDate() == null ? null : d.issuedDate().toString()),
                p.status().name(),
                money(p.settledAmount()),
                money(p.remainingAmount()),
                p.dueDate().isBefore(asOf),
                instant(p.knownAt()),
                instant(p.createdAt()),
                instant(p.updatedAt()),
                p.version());
    }

    private static RecurringPatternW pattern(RecurringPattern p) {
        var s = p.schedule();
        return new RecurringPatternW(
                p.id().toString(),
                p.accountId().toString(),
                p.direction().name(),
                p.categoryCode(),
                p.counterpartyKey(),
                p.status().name(),
                new ScheduleW(
                        s.frequency().name(),
                        s.weekday(),
                        s.dayOfMonth(),
                        s.businessDayRule().name()),
                money(p.amount()),
                p.observations(),
                p.firstSeen().toString(),
                p.lastSeen().toString(),
                p.inputDataVersion(),
                p.detectorVersion(),
                p.version());
    }

    /**
     * Recomputes the fingerprint of a stored snapshot payload; used to verify it before every
     * Forecast call.
     */
    public static String recompute(JsonNode payload) {
        return Fingerprint.of(payload);
    }
}
