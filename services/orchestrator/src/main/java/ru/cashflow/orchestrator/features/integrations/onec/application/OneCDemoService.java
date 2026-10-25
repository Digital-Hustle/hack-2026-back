package ru.cashflow.orchestrator.features.integrations.onec.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.plans.application.PlanService;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ExpectedPayment;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanDraft;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanSource;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanStatus;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.Relation;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.SourceDocument;
import ru.cashflow.orchestrator.features.plans.repository.PlanRepository;

/**
 * Imitation of a 1C integration: a fixed register of unpaid invoices and a calculated tax is loaded as ordinary
 * expected payments. Nothing is read from a real 1C. Due dates are relative to the start of the window, so the
 * documents can be placed inside the forecast window of any forecast date.
 */
@Service
public class OneCDemoService {

    public static final String EXTERNAL_SYSTEM = "1C_DEMO";

    private final CompanyAccess access;
    private final CompanyRepository companies;
    private final PlanRepository plans;
    private final PlanService planService;
    private final Clock clock;

    public OneCDemoService(CompanyAccess access, CompanyRepository companies, PlanRepository plans,
                           PlanService planService, Clock clock) {
        this.access = access;
        this.companies = companies;
        this.plans = plans;
        this.planService = planService;
        this.clock = clock;
    }

    /** One document of the imitated register; {@code dueOffset} and {@code issuedOffset} are days from the window start. */
    record Document(String externalId, PlanSource source, String number, String title, Direction direction,
                    String amount, int issuedOffset, int dueOffset, String category, String name, String inn) {
    }

    static final List<Document> REGISTER = List.of(
            new Document("tax-usn-9m-2026", PlanSource.TAX, null, "Налог по УСН за 9 месяцев (рассчитан в 1С)",
                    Direction.OUT, "25000.00", -15, 3, "TAXES", "ФНС", null),
            new Document("bill-2045", PlanSource.INVOICE, "СЧ-2045", "Счёт поставщика за оборудование",
                    Direction.OUT, "70000.00", -7, 11, "SUPPLIERS", "ООО «Поставщик»", "7707654321"),
            new Document("invoice-1001", PlanSource.INVOICE, "СФ-1001", "Счёт покупателю ООО «Ромашка»",
                    Direction.IN, "100000.00", -5, 16, "SALES", "ООО «Ромашка»", "7701234567"));

    public enum Action { CREATED, UPDATED, UNCHANGED, SKIPPED }

    public record Item(String externalId, String documentNumber, String title, Direction direction,
                       BigDecimal amount, LocalDate dueDate, Action action, String reason, UUID planId) {
    }

    public record Report(boolean dryRun, LocalDate from, int created, int updated, int unchanged, int skipped,
                         boolean dataChanged, List<Item> items) {
    }

    @Transactional
    public Report sync(UUID userId, UUID companyId, UUID accountId, LocalDate fromDate, boolean dryRun) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        LocalDate from = fromDate == null ? today(scope) : fromDate;
        List<ExpectedPayment> known = plans.listExternal(scope, EXTERNAL_SYSTEM, "");
        List<Item> items = new ArrayList<>();
        for (Document doc : REGISTER) {
            items.add(apply(userId, companyId, accountId, doc, from, known, dryRun));
        }
        int created = count(items, Action.CREATED);
        int updated = count(items, Action.UPDATED);
        return new Report(dryRun, from, created, updated, count(items, Action.UNCHANGED),
                count(items, Action.SKIPPED), !dryRun && created + updated > 0, List.copyOf(items));
    }

    private Item apply(UUID userId, UUID companyId, UUID accountId, Document doc, LocalDate from,
                       List<ExpectedPayment> known, boolean dryRun) {
        LocalDate due = from.plusDays(doc.dueOffset());
        BigDecimal amount = new BigDecimal(doc.amount());
        PlanDraft draft = new PlanDraft(doc.title(), doc.direction(), amount, due, doc.category(),
                doc.inn() == null ? null : "inn:" + doc.inn(), doc.name(), Relation.ADDITIONAL, null, null,
                doc.source(), new SourceDocument(EXTERNAL_SYSTEM, doc.externalId(), doc.number(),
                        from.plusDays(doc.issuedOffset())));
        Optional<ExpectedPayment> existing = known.stream()
                .filter(p -> p.sourceDocument() != null && doc.externalId().equals(p.sourceDocument().externalId()))
                .findFirst();
        if (existing.isEmpty()) {
            UUID planId = dryRun ? null : planService.create(userId, companyId, accountId, draft).id();
            return item(doc, due, amount, Action.CREATED, null, planId);
        }
        ExpectedPayment plan = existing.get();
        if (!plan.accountId().equals(accountId)) {
            return item(doc, plan.dueDate(), plan.amount(), Action.SKIPPED, "OTHER_ACCOUNT", plan.id());
        }
        if (plan.status() == PlanStatus.CANCELLED) {
            return item(doc, plan.dueDate(), plan.amount(), Action.SKIPPED, "CANCELLED", plan.id());
        }
        if (plan.settledAmount().signum() > 0) {
            return item(doc, plan.dueDate(), plan.amount(), Action.SKIPPED, "ALREADY_PAID", plan.id());
        }
        if (plan.dueDate().equals(due)) {
            return item(doc, due, plan.amount(), Action.UNCHANGED, null, plan.id());
        }
        if (!dryRun) {
            planService.update(userId, companyId, accountId, plan.id(), plan.version(), draft);
        }
        return item(doc, due, amount, Action.UPDATED, null, plan.id());
    }

    private static Item item(Document doc, LocalDate due, BigDecimal amount, Action action, String reason,
                             UUID planId) {
        return new Item(doc.externalId(), doc.number(), doc.title(), doc.direction(), amount, due, action, reason,
                planId);
    }

    private static int count(List<Item> items, Action action) {
        return (int) items.stream().filter(i -> i.action() == action).count();
    }

    private LocalDate today(CompanyScope scope) {
        String zone = companies.findCompany(scope).orElseThrow(ApiException::notFound).timezone();
        return LocalDate.now(clock.withZone(ZoneId.of(zone)));
    }
}
