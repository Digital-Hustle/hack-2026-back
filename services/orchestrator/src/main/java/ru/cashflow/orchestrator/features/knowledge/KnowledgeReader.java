package ru.cashflow.orchestrator.features.knowledge;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.ledger.command.DailyHistoryCalculator;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategoryRevision;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.Allocation;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ExpectedPayment;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanSource;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanStatus;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.Relation;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.SourceDocument;
import ru.cashflow.orchestrator.features.plans.repository.PlanRepository;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.BusinessDayRule;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Frequency;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.PatternStatus;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.RecurringPattern;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Schedule;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.StatusSource;
import ru.cashflow.orchestrator.features.recurring.repository.RecurringRepository;
import ru.cashflow.orchestrator.features.settings.application.SettingsService;
import ru.cashflow.orchestrator.features.settings.model.ForecastSettings;

/**
 * Historical replay: reconstructs inputs as known strictly before a cutoff from append-only facts and revisions
 * (handoff §3). It never reads today's projection or today's category/plan/pattern state for a past cutoff.
 * The same reader serves the current forecast (cutoff = now), so both paths share one implementation.
 */
@Service
public class KnowledgeReader {

    private final LedgerRepository ledger;
    private final PlanRepository plans;
    private final RecurringRepository recurring;
    private final SettingsService settings;
    private final ObjectMapper json;

    public KnowledgeReader(LedgerRepository ledger, PlanRepository plans, RecurringRepository recurring,
                           SettingsService settings, ObjectMapper json) {
        this.ledger = ledger;
        this.plans = plans;
        this.recurring = recurring;
        this.settings = settings;
        this.json = json;
    }

    /** Historical cutoff: end of business day T in the company timezone, exclusive upper bound = start of T+1. */
    public static Instant endOfDay(LocalDate day, String timezone) {
        return day.plusDays(1).atStartOfDay(ZoneId.of(timezone)).toInstant();
    }

    /**
     * @param historyFrom first business date of returned history (inclusive)
     * @param asOf        last business date of returned history (inclusive); facts after it are never returned
     * @param knownBefore knowledge cutoff (exclusive); records known at or after it are invisible
     */
    public KnowledgeState read(CompanyScope scope, UUID accountId, LocalDate historyFrom, LocalDate asOf,
                               Instant knownBefore) {
        return read(scope, accountId, historyFrom, asOf, knownBefore, null);
    }

    /** Backtest parameters belong to the experiment; account settings are not read when they are supplied. */
    public KnowledgeState read(CompanyScope scope, UUID accountId, LocalDate historyFrom, LocalDate asOf,
                               Instant knownBefore, ForecastSettings experimentSettings) {
        List<UUID> revisionsUsed = new ArrayList<>();
        var completeness = ledger.completenessKnownBefore(scope, accountId, knownBefore);
        var anchors = ledger.anchorsKnownBefore(scope, accountId, knownBefore).stream()
                .filter(a -> !a.date().isAfter(asOf)).toList();
        LocalDate anchorFrom = anchors.stream().map(a -> a.date()).min(LocalDate::compareTo).orElse(historyFrom);
        LocalDate projectionFrom = anchorFrom.isBefore(historyFrom) ? anchorFrom : historyFrom;

        var rawPayments = ledger.paymentsKnownBefore(scope, accountId, projectionFrom, asOf, knownBefore);
        Map<UUID, CategoryRevision> categories = new HashMap<>();
        ledger.revisionsKnownBefore(scope, accountId, projectionFrom, asOf, knownBefore)
                .forEach(r -> categories.put(r.paymentId(), r));
        List<Payment> paymentsAtCutoff = new ArrayList<>();
        for (Payment p : rawPayments) {
            CategoryRevision r = categories.get(p.id());
            if (r == null) {
                continue;
            }
            paymentsAtCutoff.add(new Payment(p.id(), p.companyId(), p.accountId(), p.importId(),
                    p.externalOperationId(), p.sourceRow(), p.operationDate(), p.amount(), p.direction(),
                    p.counterpartyKey(), p.counterpartyInn(), p.counterpartyName(), p.purpose(), r.categoryCode(),
                    r.categorySource(), r.ruleVersion(), p.balanceEod(), r.version(), p.knownAt(), p.createdAt()));
        }
        var allDays = DailyHistoryCalculator.calculate(projectionFrom, asOf, paymentsAtCutoff, anchors, completeness);
        var start = DailyHistoryCalculator.startBalance(asOf, anchors, allDays);
        var days = allDays.stream().filter(d -> !d.date().isBefore(historyFrom)).toList();
        var payments = paymentsAtCutoff.stream().filter(p -> !p.operationDate().isBefore(historyFrom)).toList();

        List<ExpectedPayment> planState = plansAt(scope, accountId, knownBefore, revisionsUsed);
        List<RecurringPattern> patterns = patternsAt(scope, accountId, knownBefore);
        return new KnowledgeState(scope.companyId(), accountId, asOf, knownBefore, List.copyOf(payments),
                List.copyOf(days), List.copyOf(completeness), List.copyOf(anchors), List.copyOf(planState),
                List.copyOf(patterns), experimentSettings == null
                        ? settings.knownBefore(scope, accountId, knownBefore) : experimentSettings, start,
                List.copyOf(revisionsUsed));
    }

    private List<ExpectedPayment> plansAt(CompanyScope scope, UUID accountId, Instant knownBefore,
                                          List<UUID> revisionsUsed) {
        Map<UUID, BigDecimal> settled = new HashMap<>();
        for (Allocation a : plans.allocationsForAccount(scope, accountId)) {
            if (a.activeAt(knownBefore)) {
                settled.merge(a.expectedPaymentId(), a.amount(), BigDecimal::add);
            }
        }
        List<ExpectedPayment> result = new ArrayList<>();
        for (String payload : plans.revisionPayloadsBefore(scope, accountId, knownBefore)) {
            JsonNode n = tree(payload);
            UUID id = UUID.fromString(n.path("id").asText());
            revisionsUsed.add(id);
            BigDecimal amount = new BigDecimal(n.path("amount").asText());
            BigDecimal paid = settled.getOrDefault(id, new BigDecimal("0.00")).setScale(2);
            PlanStatus stored = PlanStatus.valueOf(n.path("status").asText());
            PlanStatus status = stored == PlanStatus.CANCELLED ? PlanStatus.CANCELLED
                    : paid.signum() == 0 ? PlanStatus.OPEN
                    : paid.compareTo(amount) >= 0 ? PlanStatus.SETTLED : PlanStatus.PARTIALLY_SETTLED;
            JsonNode doc = n.path("sourceDocument");
            SourceDocument document = doc.isNull() || doc.isMissingNode() ? null
                    : new SourceDocument(doc.path("externalSystem").asText(), doc.path("externalId").asText(),
                    blank(doc.path("documentNumber").asText()),
                    blank(doc.path("issuedDate").asText()) == null ? null
                            : LocalDate.parse(doc.path("issuedDate").asText()));
            result.add(new ExpectedPayment(id, scope.companyId(), accountId, n.path("title").asText(),
                    Direction.valueOf(n.path("direction").asText()), amount,
                    LocalDate.parse(n.path("dueDate").asText()), n.path("categoryCode").asText(),
                    text(n, "counterpartyKey"), text(n, "note"), Relation.valueOf(n.path("relation").asText()),
                    text(n, "recurringPatternId") == null ? null : UUID.fromString(n.path("recurringPatternId").asText()),
                    text(n, "baselineCounterpartyKey"), PlanSource.valueOf(n.path("source").asText()), document,
                    status, paid, Instant.parse(n.path("knownAt").asText()), Instant.parse(n.path("createdAt").asText()),
                    Instant.parse(n.path("updatedAt").asText()), n.path("version").asLong()));
        }
        result.sort(java.util.Comparator.comparing(ExpectedPayment::dueDate).thenComparing(p -> p.id().toString()));
        return result;
    }

    private List<RecurringPattern> patternsAt(CompanyScope scope, UUID accountId, Instant knownBefore) {
        List<RecurringPattern> result = new ArrayList<>();
        for (String payload : recurring.revisionPayloadsBefore(scope, accountId, knownBefore)) {
            JsonNode n = tree(payload);
            JsonNode s = n.path("schedule");
            var schedule = new Schedule(Frequency.valueOf(s.path("frequency").asText()),
                    s.path("weekday").isNull() ? null : s.path("weekday").asInt(),
                    s.path("dayOfMonth").isNull() ? null : s.path("dayOfMonth").asInt(),
                    BusinessDayRule.valueOf(s.path("businessDayRule").asText()));
            result.add(new RecurringPattern(UUID.fromString(n.path("id").asText()), scope.companyId(), accountId, null,
                    Direction.valueOf(n.path("direction").asText()), n.path("categoryCode").asText(),
                    text(n, "counterpartyKey"), PatternStatus.valueOf(n.path("status").asText()),
                    StatusSource.valueOf(n.path("statusSource").asText()), schedule,
                    new BigDecimal(n.path("amount").asText()), n.path("observations").asInt(),
                    LocalDate.parse(n.path("firstSeen").asText()), LocalDate.parse(n.path("lastSeen").asText()),
                    n.path("detectorVersion").asText(), n.path("inputDataVersion").asLong(), knownBefore, knownBefore,
                    n.path("version").asLong()));
        }
        result.sort(java.util.Comparator.comparing(p -> p.id().toString()));
        return result;
    }

    private JsonNode tree(String payload) {
        try {
            return json.readTree(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
