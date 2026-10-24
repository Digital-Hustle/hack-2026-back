package ru.cashflow.orchestrator.features.plans.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.Allocation;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ExpectedPayment;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanDraft;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanStatus;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.Relation;
import ru.cashflow.orchestrator.features.plans.repository.PlanRepository;

/**
 * Expected payments are plans, never facts. Settlement goes only through allocations to real payments, checked under
 * row locks of the plan and the payment. Every change stores a full-state revision with valid_at.
 */
@Service
public class PlanService {

    private final PlanRepository plans;
    private final LedgerRepository ledger;
    private final CompanyRepository companies;
    private final CompanyAccess access;
    private final ObjectMapper json;
    private final Clock clock;

    public PlanService(PlanRepository plans, LedgerRepository ledger, CompanyRepository companies,
                       CompanyAccess access, ObjectMapper json, Clock clock) {
        this.plans = plans;
        this.ledger = ledger;
        this.companies = companies;
        this.access = access;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public ExpectedPayment create(UUID userId, UUID companyId, UUID accountId, PlanDraft draft) {
        CompanyScope scope = writeAccount(userId, companyId, accountId);
        validate(draft);
        Instant now = clock.instant();
        var plan = new ExpectedPayment(UUID.randomUUID(), scope.companyId(), accountId, draft.title().strip(),
                draft.direction(), draft.amount().setScale(2), draft.dueDate(), draft.categoryCode(),
                blank(draft.counterpartyKey()), blank(draft.note()), draft.relation(), draft.recurringPatternId(),
                blank(draft.baselineCounterpartyKey()), draft.source(), draft.sourceDocument(), PlanStatus.OPEN,
                new BigDecimal("0.00"), now, now, now, 0);
        try {
            plans.insert(plan);
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("EXTERNAL_DOCUMENT_EXISTS", "План с этим внешним документом уже существует");
        }
        plans.insertRevision(plan, payload(plan), userId);
        companies.incrementDataVersion(scope, now);
        return plan;
    }

    /** Amount may not drop below the already allocated part; direction is fixed once a payment is allocated. */
    @Transactional
    public ExpectedPayment update(UUID userId, UUID companyId, UUID accountId, UUID planId, long version,
                                  PlanDraft draft) {
        CompanyScope scope = writeAccount(userId, companyId, accountId);
        validate(draft);
        ExpectedPayment current = plans.lock(scope, accountId, planId).orElseThrow(ApiException::notFound);
        requireVersion(current, version);
        if (current.status() == PlanStatus.CANCELLED) {
            throw ApiException.conflict("PLAN_CANCELLED", "Отменённый план нельзя изменить");
        }
        BigDecimal amount = draft.amount().setScale(2);
        if (amount.compareTo(current.settledAmount()) < 0) {
            throw ApiException.unprocessable("AMOUNT_BELOW_SETTLED", "Сумма плана меньше уже распределённой оплаты");
        }
        if (current.settledAmount().signum() > 0 && draft.direction() != current.direction()) {
            throw ApiException.unprocessable("DIRECTION_LOCKED", "Нельзя менять направление частично оплаченного плана");
        }
        Instant now = clock.instant();
        var updated = new ExpectedPayment(current.id(), current.companyId(), accountId, draft.title().strip(),
                draft.direction(), amount, draft.dueDate(), draft.categoryCode(), blank(draft.counterpartyKey()),
                blank(draft.note()), draft.relation(), draft.recurringPatternId(), blank(draft.baselineCounterpartyKey()),
                draft.source(), draft.sourceDocument(), status(amount, current.settledAmount()),
                current.settledAmount(), current.knownAt(), current.createdAt(), now, version + 1);
        return persist(scope, updated, version, userId, now);
    }

    @Transactional
    public ExpectedPayment cancel(UUID userId, UUID companyId, UUID accountId, UUID planId, long version) {
        CompanyScope scope = writeAccount(userId, companyId, accountId);
        ExpectedPayment current = plans.lock(scope, accountId, planId).orElseThrow(ApiException::notFound);
        requireVersion(current, version);
        if (current.status() == PlanStatus.CANCELLED) {
            return current;
        }
        Instant now = clock.instant();
        var cancelled = withState(current, PlanStatus.CANCELLED, current.settledAmount(), now);
        return persist(scope, cancelled, version, userId, now);
    }

    @Transactional
    public Allocation allocate(UUID userId, UUID companyId, UUID accountId, UUID planId, UUID paymentId,
                               BigDecimal amount) {
        CompanyScope scope = writeAccount(userId, companyId, accountId);
        BigDecimal value = amount.setScale(2);
        if (value.signum() <= 0) {
            throw ApiException.unprocessable("INVALID_AMOUNT", "Сумма распределения должна быть больше нуля");
        }
        ExpectedPayment plan = plans.lock(scope, accountId, planId).orElseThrow(ApiException::notFound);
        Payment payment = ledger.lockPayment(scope, accountId, paymentId).orElseThrow(ApiException::notFound);
        if (ledger.findPaymentView(scope, accountId, paymentId).orElseThrow().voidedAt() != null) {
            throw ApiException.conflict("PAYMENT_VOIDED", "Платёж аннулирован");
        }
        if (plan.status() == PlanStatus.CANCELLED) {
            throw ApiException.conflict("PLAN_CANCELLED", "Нельзя распределить оплату на отменённый план");
        }
        if (plan.direction() != payment.direction()) {
            throw ApiException.unprocessable("DIRECTION_MISMATCH", "Направление оплаты не совпадает с планом");
        }
        if (plan.settledAmount().add(value).compareTo(plan.amount()) > 0) {
            throw ApiException.unprocessable("PLAN_OVER_ALLOCATED", "Распределение превышает сумму плана");
        }
        if (plans.activeAllocatedToPayment(scope, paymentId).add(value).compareTo(payment.amount()) > 0) {
            throw ApiException.unprocessable("PAYMENT_OVER_ALLOCATED", "Распределение превышает сумму оплаты");
        }
        Instant now = clock.instant();
        var allocation = new Allocation(UUID.randomUUID(), planId, paymentId, value, now, userId, null, null);
        try {
            plans.insertAllocation(scope, accountId, allocation);
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("ALLOCATION_EXISTS", "Оплата уже связана с этим планом");
        }
        BigDecimal settled = plans.activeAllocatedToPlan(planId);
        persist(scope, withState(plan, status(plan.amount(), settled), settled, now), plan.version(), userId, now);
        return allocation;
    }

    /** Reversal keeps the allocation row with reversed_at, so the history at earlier cutoffs is preserved. */
    @Transactional
    public void reverse(UUID userId, UUID companyId, UUID accountId, UUID allocationId) {
        CompanyScope scope = writeAccount(userId, companyId, accountId);
        Allocation allocation = plans.lockAllocation(scope, accountId, allocationId).orElseThrow(ApiException::notFound);
        if (allocation.reversedAt() != null) {
            return;
        }
        ExpectedPayment plan = plans.lock(scope, accountId, allocation.expectedPaymentId())
                .orElseThrow(ApiException::notFound);
        Instant now = clock.instant();
        plans.reverseAllocation(allocationId, now, userId);
        BigDecimal settled = plans.activeAllocatedToPlan(plan.id());
        PlanStatus next = plan.status() == PlanStatus.CANCELLED ? PlanStatus.CANCELLED : status(plan.amount(), settled);
        persist(scope, withState(plan, next, settled, now), plan.version(), userId, now);
    }

    /** Replaces allocation identities atomically without changing net settlement, including cancelled plans. */
    @Transactional
    public void transfer(UUID userId, UUID companyId, UUID accountId, UUID sourceId, UUID targetId) {
        CompanyScope scope = writeAccount(userId, companyId, accountId);
        Payment source = ledger.lockPayment(scope, accountId, sourceId).orElseThrow(ApiException::notFound);
        Payment target = ledger.lockPayment(scope, accountId, targetId).orElseThrow(ApiException::notFound);
        if (source.direction() != target.direction()) {
            throw ApiException.unprocessable("DIRECTION_MISMATCH", "Направления не совпадают");
        }
        var allocations = plans.allocationsForAccount(scope, accountId);
        var moving = allocations.stream().filter(a -> a.paymentId().equals(sourceId) && a.reversedAt() == null).toList();
        BigDecimal total = moving.stream().map(Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (plans.activeAllocatedToPayment(scope, targetId).add(total).compareTo(target.amount()) > 0) {
            throw ApiException.conflict("PAYMENT_OVER_ALLOCATED", "Для переноса распределений недостаточно суммы");
        }
        Instant now = clock.instant();
        for (Allocation allocation : moving) {
            ExpectedPayment plan = plans.lock(scope, accountId, allocation.expectedPaymentId()).orElseThrow();
            BigDecimal amount = allocation.amount();
            plans.reverseAllocation(allocation.id(), now, userId);
            for (Allocation existing : allocations) {
                if (existing.paymentId().equals(targetId) && existing.reversedAt() == null
                        && existing.expectedPaymentId().equals(plan.id())) {
                    amount = amount.add(existing.amount());
                    plans.reverseAllocation(existing.id(), now, userId);
                }
            }
            plans.insertAllocation(scope, accountId,
                    new Allocation(UUID.randomUUID(), plan.id(), targetId, amount, now, userId, null, null));
            persist(scope, withState(plan, plan.status(), plan.settledAmount(), now), plan.version(), userId, now);
        }
    }

    public List<ExpectedPayment> list(UUID userId, UUID companyId, UUID accountId, LocalDate from, LocalDate to) {
        CompanyScope scope = access.requireRead(userId, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        return plans.list(scope, accountId, from, to);
    }

    public ExpectedPayment get(UUID userId, UUID companyId, UUID accountId, UUID planId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        return plans.find(scope, accountId, planId).orElseThrow(ApiException::notFound);
    }

    public List<Allocation> allocations(UUID userId, UUID companyId, UUID accountId, UUID planId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        plans.find(scope, accountId, planId).orElseThrow(ApiException::notFound);
        return plans.allocations(scope, planId);
    }

    /** Full state stored in expected_payment_revisions.payload; historical replay rebuilds plans from it. */
    public String payload(ExpectedPayment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id().toString());
        m.put("accountId", p.accountId().toString());
        m.put("title", p.title());
        m.put("direction", p.direction().name());
        m.put("amount", p.amount().toPlainString());
        m.put("dueDate", p.dueDate().toString());
        m.put("categoryCode", p.categoryCode());
        m.put("counterpartyKey", p.counterpartyKey());
        m.put("note", p.note());
        m.put("relation", p.relation().name());
        m.put("recurringPatternId", p.recurringPatternId() == null ? null : p.recurringPatternId().toString());
        m.put("baselineCounterpartyKey", p.baselineCounterpartyKey());
        m.put("source", p.source().name());
        var d = p.sourceDocument();
        m.put("sourceDocument", d == null ? null : Map.of("externalSystem", d.externalSystem(), "externalId",
                d.externalId(), "documentNumber", d.documentNumber() == null ? "" : d.documentNumber(),
                "issuedDate", d.issuedDate() == null ? "" : d.issuedDate().toString()));
        m.put("status", p.status().name());
        m.put("settledAmount", p.settledAmount().toPlainString());
        m.put("knownAt", p.knownAt().toString());
        m.put("createdAt", p.createdAt().toString());
        m.put("updatedAt", p.updatedAt().toString());
        m.put("version", p.version());
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ExpectedPayment persist(CompanyScope scope, ExpectedPayment next, long expectedVersion, UUID userId,
                                    Instant now) {
        var versioned = new ExpectedPayment(next.id(), next.companyId(), next.accountId(), next.title(),
                next.direction(), next.amount(), next.dueDate(), next.categoryCode(), next.counterpartyKey(),
                next.note(), next.relation(), next.recurringPatternId(), next.baselineCounterpartyKey(), next.source(),
                next.sourceDocument(), next.status(), next.settledAmount(), next.knownAt(), next.createdAt(), now,
                expectedVersion + 1);
        if (!plans.update(versioned, expectedVersion)) {
            throw ApiException.conflict("VERSION_CONFLICT", "План изменён другим запросом; обновите данные");
        }
        plans.insertRevision(versioned, payload(versioned), userId);
        companies.incrementDataVersion(scope, now);
        return versioned;
    }

    private static ExpectedPayment withState(ExpectedPayment p, PlanStatus status, BigDecimal settled, Instant now) {
        return new ExpectedPayment(p.id(), p.companyId(), p.accountId(), p.title(), p.direction(), p.amount(),
                p.dueDate(), p.categoryCode(), p.counterpartyKey(), p.note(), p.relation(), p.recurringPatternId(),
                p.baselineCounterpartyKey(), p.source(), p.sourceDocument(), status, settled, p.knownAt(),
                p.createdAt(), now, p.version());
    }

    private static PlanStatus status(BigDecimal amount, BigDecimal settled) {
        if (settled.signum() == 0) {
            return PlanStatus.OPEN;
        }
        return settled.compareTo(amount) >= 0 ? PlanStatus.SETTLED : PlanStatus.PARTIALLY_SETTLED;
    }

    private static void validate(PlanDraft d) {
        if (d.amount().signum() <= 0 || d.amount().scale() > 2) {
            throw ApiException.unprocessable("INVALID_AMOUNT", "Сумма плана должна быть больше нуля, 2 знака");
        }
        boolean consistent = switch (d.relation()) {
            case ADDITIONAL -> d.recurringPatternId() == null && blank(d.baselineCounterpartyKey()) == null;
            case REPLACES_RECURRING -> d.recurringPatternId() != null && blank(d.baselineCounterpartyKey()) == null;
            case REPLACES_BASELINE -> d.recurringPatternId() == null && blank(d.baselineCounterpartyKey()) != null;
        };
        if (!consistent) {
            throw ApiException.unprocessable("UNRESOLVED_PLAN",
                    "relation требует ровно одну связь: REPLACES_RECURRING — recurringPatternId, "
                            + "REPLACES_BASELINE — baselineCounterpartyKey, ADDITIONAL — ни одной");
        }
        if (d.relation() == Relation.REPLACES_BASELINE && blank(d.counterpartyKey()) == null) {
            throw ApiException.unprocessable("UNRESOLVED_PLAN", "REPLACES_BASELINE требует counterpartyKey");
        }
    }

    private static void requireVersion(ExpectedPayment current, long version) {
        if (current.version() != version) {
            throw ApiException.conflict("VERSION_CONFLICT", "План изменён другим запросом; обновите данные");
        }
    }

    private CompanyScope writeAccount(UUID userId, UUID companyId, UUID accountId) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        companies.lockCompany(scope).orElseThrow(ApiException::notFound);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        return scope;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
