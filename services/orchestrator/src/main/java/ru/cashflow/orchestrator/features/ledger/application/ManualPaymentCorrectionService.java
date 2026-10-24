package ru.cashflow.orchestrator.features.ledger.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.jobs.repository.IdempotencyRepository;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.PaymentSource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.PaymentView;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.features.plans.application.PlanService;
import ru.cashflow.orchestrator.features.plans.repository.PlanRepository;

/** Append-only correction events preserve the facts visible before each knowledge cutoff. */
@Service
@Transactional
public class ManualPaymentCorrectionService {
    public enum Decision { SAME, DIFFERENT }

    private final CompanyAccess access;
    private final CompanyRepository companies;
    private final LedgerRepository ledger;
    private final LedgerService history;
    private final PlanRepository plans;
    private final PlanService settlement;
    private final IdempotencyRepository audit;
    private final JdbcClient jdbc;
    private final Clock clock;

    public ManualPaymentCorrectionService(CompanyAccess access, CompanyRepository companies, LedgerRepository ledger,
                                         LedgerService history, PlanRepository plans, PlanService settlement,
                                         IdempotencyRepository audit, JdbcClient jdbc, Clock clock) {
        this.access = access;
        this.companies = companies;
        this.ledger = ledger;
        this.history = history;
        this.plans = plans;
        this.settlement = settlement;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public PaymentView voidPayment(UUID user, UUID company, UUID id, long version, String reason) {
        CompanyScope scope = write(user, company);
        Payment payment = manual(scope, id);
        PaymentView view = view(scope, payment);
        requireVersion(payment, version);
        if (view.voidedAt() != null) {
            throw ApiException.conflict("PAYMENT_VOIDED", "Платёж уже аннулирован");
        }
        annul(scope, payment, reason.strip(), null);
        return view(scope, payment);
    }

    /** Explicit user confirmation; a similarity match alone never merges facts. */
    public PaymentView resolve(UUID user, UUID company, UUID id, long version, UUID importedId,
                               long importedVersion, Decision decision, String reason) {
        CompanyScope scope = write(user, company);
        Payment manual = manual(scope, id);
        Payment imported = ledger.lockPayment(scope, manual.accountId(), importedId).orElseThrow(ApiException::notFound);
        var previous = jdbc.sql("select decision from manual_duplicate_resolutions "
                        + "where company_id=:company and manual_payment_id=:manual and imported_payment_id=:imported")
                .param("company", company).param("manual", id).param("imported", importedId)
                .query(String.class).optional();
        if (previous.isPresent()) {
            if (!previous.get().equals(decision.name())) {
                throw ApiException.conflict("DECISION_EXISTS", "Решение уже принято");
            }
            return view(scope, manual);
        }
        requireVersion(manual, version);
        requireVersion(imported, importedVersion);
        if (view(scope, manual).voidedAt() != null || imported.source() != PaymentSource.IMPORT
                || !manual.operationDate().equals(imported.operationDate())
                || manual.direction() != imported.direction() || manual.amount().compareTo(imported.amount()) != 0) {
            throw ApiException.unprocessable("INVALID_DUPLICATE", "Операции нельзя сопоставить");
        }
        if (decision == Decision.SAME) {
            settlement.transfer(user, company, manual.accountId(), id, importedId);
            annul(scope, manual, reason.strip(), importedId);
        }
        Instant now = clock.instant();
        jdbc.sql("insert into manual_duplicate_resolutions values (:manual,:imported,:company,:decision,:now,:actor)")
                .param("manual", id).param("imported", importedId).param("company", company)
                .param("decision", decision.name()).param("now", Timestamp.from(now)).param("actor", user).update();
        audit.audit(scope, "MANUAL_DUPLICATE_" + decision.name(), "PAYMENT", id, now);
        return view(scope, manual);
    }

    @Transactional(readOnly = true)
    public List<PaymentView> candidates(UUID user, UUID company, UUID id) {
        CompanyScope scope = access.requireRead(user, company);
        Payment payment = ledger.findPaymentForCompany(scope, id).orElseThrow(ApiException::notFound);
        if (payment.source() != PaymentSource.MANUAL || view(scope, payment).voidedAt() != null) {
            return List.of();
        }
        List<UUID> ids = jdbc.sql("select p.id from payments p where p.company_id=:company and p.account_id=:account "
                        + "and p.source='IMPORT' and p.operation_date=:date and p.direction=:direction and p.amount=:amount "
                        + "and not exists (select 1 from manual_duplicate_resolutions r "
                        + "where r.manual_payment_id=:manual and r.imported_payment_id=p.id) order by p.id limit 200")
                .param("company", company).param("account", payment.accountId()).param("date", payment.operationDate())
                .param("direction", payment.direction().name()).param("amount", payment.amount()).param("manual", id)
                .query(UUID.class).list();
        return ids.isEmpty() ? List.of() : ledger.paymentViews(scope, ids);
    }

    private CompanyScope write(UUID user, UUID company) {
        CompanyScope scope = access.requireWrite(user, company);
        companies.lockCompany(scope).orElseThrow(ApiException::notFound);
        return scope;
    }

    private Payment manual(CompanyScope scope, UUID id) {
        Payment payment = ledger.lockPaymentForCompany(scope, id).orElseThrow(ApiException::notFound);
        if (payment.source() != PaymentSource.MANUAL) {
            throw ApiException.unprocessable("NOT_MANUAL", "Исправлять можно только ручной платёж");
        }
        if (companies.findAccount(scope, payment.accountId()).orElseThrow(ApiException::notFound).archived()) {
            throw ApiException.forbidden();
        }
        return payment;
    }

    private PaymentView view(CompanyScope scope, Payment payment) {
        return ledger.findPaymentView(scope, payment.accountId(), payment.id()).orElseThrow(ApiException::notFound);
    }

    private static void requireVersion(Payment payment, long version) {
        if (version != payment.version() + 1) {
            throw ApiException.conflict("VERSION_CONFLICT", "Платёж изменён; обновите данные");
        }
    }

    private void annul(CompanyScope scope, Payment payment, String reason, UUID replacement) {
        Instant now = clock.instant();
        for (var allocation : plans.allocationsForAccount(scope, payment.accountId())) {
            if (allocation.paymentId().equals(payment.id()) && allocation.reversedAt() == null) {
                settlement.reverse(scope.userId(), scope.companyId(), payment.accountId(), allocation.id());
            }
        }
        jdbc.sql("insert into payment_voids values (:id,:company,:account,:now,:actor,:reason,:replacement)")
                .param("id", payment.id()).param("company", scope.companyId()).param("account", payment.accountId())
                .param("now", Timestamp.from(now)).param("actor", scope.userId()).param("reason", reason)
                .param("replacement", replacement).update();
        jdbc.sql("update payments set version=version+1 where id=:id and company_id=:company")
                .param("id", payment.id()).param("company", scope.companyId()).update();
        // A corrected day needs a new explicit completeness confirmation; never infer a confirmed zero.
        if (replacement == null) {
            ledger.insertCompleteness(scope, new CompletenessRange(
                UUID.randomUUID(), payment.accountId(), payment.operationDate(), payment.operationDate(),
                Completeness.UNKNOWN,
                "Ручной факт исправлен: проверьте полноту дня", now, scope.userId()));
        }
        long dataVersion = companies.incrementDataVersion(scope, now);
        history.rebuildProjection(scope, payment.accountId(), dataVersion);
        audit.audit(scope, "MANUAL_PAYMENT_VOIDED", "PAYMENT", payment.id(), now);
    }
}
