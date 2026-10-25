package ru.cashflow.orchestrator.features.integrations.bitrix.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Action;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.BitrixDocument;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.DocumentKind;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Stage;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.SyncItem;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.SyncOptions;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ExpectedPayment;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanSource;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanStatus;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.Relation;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.SourceDocument;

/**
 * Pure decision step of the sync: Bitrix24 documents + already synced plans → what to create, update, reopen or
 * cancel. No I/O, so the rules are unit-tested directly.
 *
 * <ul>
 *   <li>Open RUB document with an amount and a due date in the horizon → plan (IN, ADDITIONAL, SALES by default);
 *       deals use source CONTRACT, invoices INVOICE; externalId = {@code <portal>/<deal|invoice>/<id>}.</li>
 *   <li>Won deal / paid invoice, lost deal / declined invoice, other currency → its plan is cancelled: the money
 *       either already is a bank fact or will not come.</li>
 *   <li>A synced plan of this account whose document is no longer returned for the horizon → cancelled.</li>
 *   <li>Plans of the same document on another account of the company are never touched.</li>
 *   <li>A plan is never lowered below the amount already allocated to bank payments.</li>
 * </ul>
 */
public final class BitrixSyncPlanner {

    static final int MAX_TITLE = 200;
    static final int MAX_NOTE = 1000;
    static final int MAX_EXTERNAL_ID = 128;
    static final int MAX_DOCUMENT_NUMBER = 64;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private BitrixSyncPlanner() {
    }

    /** {@code next} is the full new plan state (version already incremented for changes of existing plans). */
    public record Decision(SyncItem item, ExpectedPayment existing, ExpectedPayment next) {

        public boolean changes() {
            return next != null;
        }
    }

    public static List<Decision> plan(UUID companyId, UUID accountId, String portalHost, List<BitrixDocument> documents,
                                      List<ExpectedPayment> synced, SyncOptions options, LocalDate from, LocalDate to,
                                      Instant now) {
        Map<String, ExpectedPayment> byExternalId = new HashMap<>();
        for (ExpectedPayment plan : synced) {
            if (plan.sourceDocument() != null) {
                byExternalId.put(plan.sourceDocument().externalId(), plan);
            }
        }
        Map<String, BitrixDocument> unique = new LinkedHashMap<>();
        for (BitrixDocument document : documents) {
            if (included(document.kind(), options)) {
                unique.putIfAbsent(externalId(portalHost, document.kind(), document.id()), document);
            }
        }
        List<Decision> decisions = new ArrayList<>();
        for (var entry : unique.entrySet()) {
            decisions.add(decide(companyId, accountId, entry.getKey(), entry.getValue(),
                    byExternalId.get(entry.getKey()), options, from, to, now));
        }
        for (ExpectedPayment plan : synced) {
            String externalId = plan.sourceDocument() == null ? null : plan.sourceDocument().externalId();
            DocumentKind kind = kindOf(externalId);
            if (externalId == null || unique.containsKey(externalId) || kind == null || !included(kind, options)
                    || !plan.accountId().equals(accountId) || plan.status() == PlanStatus.CANCELLED
                    || plan.dueDate().isBefore(from) || plan.dueDate().isAfter(to)) {
                continue;
            }
            decisions.add(new Decision(item(kind, externalId, plan.title(), null, null, plan.dueDate(), null,
                    null, Action.CANCELLED, "NOT_IN_BITRIX", plan.id()), plan, cancelled(plan, now)));
        }
        decisions.sort(Comparator.comparing((Decision d) -> d.item().dueDate(),
                Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(d -> d.item().externalId()));
        return decisions;
    }

    private static Decision decide(UUID companyId, UUID accountId, String externalId, BitrixDocument document,
                                   ExpectedPayment existing, SyncOptions options, LocalDate from, LocalDate to,
                                   Instant now) {
        BigDecimal planAmount = planAmount(document, options.weightByProbability());
        if (existing != null && !existing.accountId().equals(accountId)) {
            return skipped(externalId, document, null, "OTHER_ACCOUNT", existing.id());
        }
        if (externalId.length() > MAX_EXTERNAL_ID) {
            return skipped(externalId, document, null, "EXTERNAL_ID_TOO_LONG", null);
        }
        String reason = ineligibility(document, planAmount, from, to);
        if (reason != null) {
            if (existing != null && existing.status() != PlanStatus.CANCELLED) {
                return new Decision(item(document, externalId, null, Action.CANCELLED, reason, existing.id()),
                        existing, cancelled(existing, now));
            }
            return skipped(externalId, document, null, reason, existing == null ? null : existing.id());
        }
        if (existing == null) {
            ExpectedPayment created = new ExpectedPayment(UUID.randomUUID(), companyId, accountId, title(document),
                    Direction.IN, planAmount, document.dueDate(), options.categoryCode(), counterpartyKey(document),
                    note(document, planAmount), Relation.ADDITIONAL, null, null, source(document.kind()),
                    sourceDocument(externalId, document), PlanStatus.OPEN, ZERO, now, now, now, 0);
            return new Decision(item(document, externalId, planAmount, Action.CREATED, null, created.id()), null,
                    created);
        }
        if (planAmount.compareTo(existing.settledAmount()) < 0) {
            return skipped(externalId, document, planAmount, "AMOUNT_BELOW_SETTLED", existing.id());
        }
        PlanStatus status = status(planAmount, existing.settledAmount());
        ExpectedPayment next = new ExpectedPayment(existing.id(), existing.companyId(), accountId, title(document),
                Direction.IN, planAmount, document.dueDate(), options.categoryCode(), counterpartyKey(document),
                note(document, planAmount), Relation.ADDITIONAL, null, null, source(document.kind()),
                sourceDocument(externalId, document), status, existing.settledAmount(), existing.knownAt(),
                existing.createdAt(), now, existing.version() + 1);
        if (existing.status() == PlanStatus.CANCELLED) {
            return new Decision(item(document, externalId, planAmount, Action.REOPENED, null, existing.id()),
                    existing, next);
        }
        if (sameContent(existing, next)) {
            return new Decision(item(document, externalId, planAmount, Action.UNCHANGED, null, existing.id()),
                    existing, null);
        }
        return new Decision(item(document, externalId, planAmount, Action.UPDATED, null, existing.id()), existing,
                next);
    }

    static String ineligibility(BitrixDocument document, BigDecimal planAmount, LocalDate from, LocalDate to) {
        if (document.stage() == Stage.WON) {
            return "WON";
        }
        if (document.stage() == Stage.LOST) {
            return "LOST";
        }
        if (document.currency() != null && !"RUB".equalsIgnoreCase(document.currency())) {
            return "CURRENCY_NOT_RUB";
        }
        if (document.dueDate() == null) {
            return "NO_DUE_DATE";
        }
        if (document.dueDate().isBefore(from) || document.dueDate().isAfter(to)) {
            return "OUT_OF_HORIZON";
        }
        if (planAmount == null || planAmount.signum() <= 0) {
            return "NO_AMOUNT";
        }
        return null;
    }

    /** Full amount, or for deals optionally weighted by the stage probability (expected rather than promised money). */
    static BigDecimal planAmount(BitrixDocument document, boolean weightByProbability) {
        if (document.amount() == null) {
            return null;
        }
        BigDecimal amount = document.amount().setScale(2, RoundingMode.HALF_UP);
        if (weightByProbability && document.kind() == DocumentKind.DEAL && document.probability() != null) {
            amount = amount.multiply(BigDecimal.valueOf(document.probability())).divide(HUNDRED, 2,
                    RoundingMode.HALF_UP);
        }
        return amount;
    }

    public static String externalId(String portalHost, DocumentKind kind, String id) {
        return portalHost + "/" + kind.path() + "/" + id;
    }

    static DocumentKind kindOf(String externalId) {
        if (externalId == null) {
            return null;
        }
        String[] parts = externalId.split("/");
        if (parts.length < 3) {
            return null;
        }
        for (DocumentKind kind : DocumentKind.values()) {
            if (kind.path().equals(parts[parts.length - 2])) {
                return kind;
            }
        }
        return null;
    }

    static String title(BitrixDocument d) {
        String name = d.title() == null ? "" : d.title();
        String title = d.kind() == DocumentKind.DEAL
                ? "Битрикс24 · сделка " + d.id() + (name.isEmpty() ? "" : ": " + name)
                : "Битрикс24 · счёт № " + d.documentNumber() + (name.isEmpty() ? "" : ": " + name);
        return cut(title, MAX_TITLE);
    }

    static String note(BitrixDocument d, BigDecimal planAmount) {
        var note = new StringBuilder("Синхронизировано из Битрикс24");
        if (d.stageCode() != null) {
            note.append(", стадия ").append(d.stageCode());
        }
        if (d.probability() != null) {
            note.append(", вероятность ").append(d.probability()).append('%');
        }
        if (d.amount() != null && planAmount != null
                && d.amount().setScale(2, RoundingMode.HALF_UP).compareTo(planAmount) != 0) {
            note.append(", сумма документа ").append(d.amount().setScale(2, RoundingMode.HALF_UP).toPlainString())
                    .append(" учтена с вероятностью");
        }
        if (d.companyName() != null) {
            note.append(", плательщик: ").append(d.companyName());
        }
        return cut(note.toString(), MAX_NOTE);
    }

    static String counterpartyKey(BitrixDocument d) {
        return d.companyId() == null ? null : "bitrix24:company:" + d.companyId();
    }

    private static SourceDocument sourceDocument(String externalId, BitrixDocument d) {
        String number = d.documentNumber() == null ? null : cut(d.documentNumber(), MAX_DOCUMENT_NUMBER);
        return new SourceDocument(BitrixTypes.EXTERNAL_SYSTEM, externalId, number, d.issuedDate());
    }

    private static PlanSource source(DocumentKind kind) {
        return kind == DocumentKind.DEAL ? PlanSource.CONTRACT : PlanSource.INVOICE;
    }

    private static boolean included(DocumentKind kind, SyncOptions options) {
        return kind == DocumentKind.DEAL ? options.includeDeals() : options.includeInvoices();
    }

    private static boolean sameContent(ExpectedPayment a, ExpectedPayment b) {
        return a.title().equals(b.title()) && a.direction() == b.direction() && a.amount().compareTo(b.amount()) == 0
                && a.dueDate().equals(b.dueDate()) && a.categoryCode().equals(b.categoryCode())
                && Objects.equals(a.counterpartyKey(), b.counterpartyKey()) && Objects.equals(a.note(), b.note())
                && a.relation() == b.relation() && a.recurringPatternId() == null
                && a.baselineCounterpartyKey() == null && a.source() == b.source()
                && Objects.equals(a.sourceDocument(), b.sourceDocument()) && a.status() == b.status();
    }

    private static ExpectedPayment cancelled(ExpectedPayment p, Instant now) {
        return new ExpectedPayment(p.id(), p.companyId(), p.accountId(), p.title(), p.direction(), p.amount(),
                p.dueDate(), p.categoryCode(), p.counterpartyKey(), p.note(), p.relation(), p.recurringPatternId(),
                p.baselineCounterpartyKey(), p.source(), p.sourceDocument(), PlanStatus.CANCELLED, p.settledAmount(),
                p.knownAt(), p.createdAt(), now, p.version() + 1);
    }

    private static PlanStatus status(BigDecimal amount, BigDecimal settled) {
        if (settled.signum() == 0) {
            return PlanStatus.OPEN;
        }
        return settled.compareTo(amount) >= 0 ? PlanStatus.SETTLED : PlanStatus.PARTIALLY_SETTLED;
    }

    private static Decision skipped(String externalId, BitrixDocument d, BigDecimal planAmount, String reason,
                                    UUID planId) {
        return new Decision(item(d, externalId, planAmount, Action.SKIPPED, reason, planId), null, null);
    }

    private static SyncItem item(BitrixDocument d, String externalId, BigDecimal planAmount, Action action,
                                 String reason, UUID planId) {
        return item(d.kind(), externalId, d.title(), d.amount(), planAmount, d.dueDate(), d.stage(), d.probability(),
                action, reason, planId);
    }

    private static SyncItem item(DocumentKind kind, String externalId, String title, BigDecimal documentAmount,
                                 BigDecimal planAmount, LocalDate dueDate, Stage stage, Integer probability,
                                 Action action, String reason, UUID planId) {
        return new SyncItem(kind, externalId, title, documentAmount, planAmount, dueDate, stage, probability, action,
                reason, planId);
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
