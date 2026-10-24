package ru.cashflow.orchestrator.features.ledger.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.ledger.command.DailyHistoryCalculator;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.AnchorSource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategoryRevision;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.DailyObservation;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;

@Service
public class LedgerService {

    private static final int MAX_PAGE = 500;
    private static final long MAX_RANGE_DAYS = 3660;

    private final LedgerRepository ledger;
    private final CompanyRepository companies;
    private final CompanyAccess access;
    private final Clock clock;

    public LedgerService(LedgerRepository ledger, CompanyRepository companies, CompanyAccess access, Clock clock) {
        this.ledger = ledger;
        this.companies = companies;
        this.access = access;
        this.clock = clock;
    }

    public List<Payment> payments(UUID userId, UUID companyId, UUID accountId, LocalDate from, LocalDate to,
                                  int limit, int offset) {
        CompanyScope scope = readAccount(userId, companyId, accountId);
        validateRange(from, to);
        if (limit < 1 || limit > MAX_PAGE || offset < 0) {
            throw ApiException.unprocessable("INVALID_PAGE", "limit 1..500, offset >= 0");
        }
        return ledger.payments(scope, accountId, from, to, limit, offset);
    }

    public List<CategoryRevision> revisions(UUID userId, UUID companyId, UUID accountId, UUID paymentId) {
        CompanyScope scope = readAccount(userId, companyId, accountId);
        ledger.findPayment(scope, accountId, paymentId).orElseThrow(ApiException::notFound);
        return ledger.revisions(scope, paymentId);
    }

    /** A user correction appends a revision; the immutable fact (date, amount, direction) is never rewritten. */
    @Transactional
    public Payment changeCategory(UUID userId, UUID companyId, UUID accountId, UUID paymentId, long version,
                                  String categoryCode) {
        CompanyScope scope = writeAccount(userId, companyId, accountId);
        Payment payment = ledger.lockPayment(scope, accountId, paymentId).orElseThrow(ApiException::notFound);
        if (payment.version() != version) {
            throw ApiException.conflict("VERSION_CONFLICT", "Платёж изменён другим запросом; обновите данные");
        }
        Instant now = clock.instant();
        ledger.updateCategory(scope, paymentId, version, categoryCode, CategorySource.USER, null);
        ledger.insertRevision(scope.companyId(), new CategoryRevision(paymentId, categoryCode, CategorySource.USER,
                null, now, userId, version + 1));
        companies.incrementDataVersion(scope, now);
        return ledger.findPayment(scope, accountId, paymentId).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public CompletenessRange addCompleteness(UUID userId, UUID companyId, UUID accountId, LocalDate from,
                                             LocalDate to, Completeness completeness, String reason) {
        CompanyScope scope = writeAccount(userId, companyId, accountId);
        validateRange(from, to);
        LocalDate today = LocalDate.now(clock.withZone(java.time.ZoneId.of(
                companies.findCompany(scope).orElseThrow(ApiException::notFound).timezone())));
        if (!to.isBefore(today)) {
            throw ApiException.unprocessable("OPEN_DAY", "Полным можно отметить только закрытые прошедшие дни");
        }
        Instant now = clock.instant();
        var range = new CompletenessRange(UUID.randomUUID(), accountId, from, to, completeness, reason.strip(), now,
                userId);
        ledger.insertCompleteness(scope, range);
        long version = companies.incrementDataVersion(scope, now);
        rebuildProjection(scope, accountId, version);
        return range;
    }

    /** Manual anchors are appended and may supersede a statement anchor; the statement row stays intact. */
    @Transactional
    public BalanceAnchor addManualAnchor(UUID userId, UUID companyId, UUID accountId, LocalDate date,
                                         BigDecimal balance, String reason, UUID supersedesId) {
        CompanyScope scope = writeAccount(userId, companyId, accountId);
        if (supersedesId != null) {
            ledger.findAnchor(scope, accountId, supersedesId).orElseThrow(ApiException::notFound);
        }
        Instant now = clock.instant();
        var anchor = new BalanceAnchor(UUID.randomUUID(), accountId, date, balance.setScale(2), AnchorSource.MANUAL,
                reason.strip(), now, userId, supersedesId);
        ledger.insertAnchor(scope, anchor, null);
        long version = companies.incrementDataVersion(scope, now);
        rebuildProjection(scope, accountId, version);
        return anchor;
    }

    public List<DailyObservation> observations(UUID userId, UUID companyId, UUID accountId, LocalDate from,
                                               LocalDate to) {
        CompanyScope scope = readAccount(userId, companyId, accountId);
        validateRange(from, to);
        return ledger.observations(scope, accountId, from, to);
    }

    /** Recomputes the live projection from all currently known inputs. Called in the same transaction as writes. */
    public void rebuildProjection(CompanyScope scope, UUID accountId, long dataVersion) {
        var range = ledger.paymentDateRange(scope, accountId);
        Instant knownBefore = clock.instant().plusNanos(1);
        List<CompletenessRange> ranges = ledger.completenessKnownBefore(scope, accountId, knownBefore);
        List<BalanceAnchor> anchors = ledger.anchorsKnownBefore(scope, accountId, knownBefore);
        LocalDate from = range.map(r -> r[0]).orElse(null);
        LocalDate to = range.map(r -> r[1]).orElse(null);
        for (CompletenessRange r : ranges) {
            from = from == null || r.fromDate().isBefore(from) ? r.fromDate() : from;
            to = to == null || r.toDate().isAfter(to) ? r.toDate() : to;
        }
        if (from == null) {
            ledger.replaceObservations(scope, accountId, List.of(), dataVersion);
            return;
        }
        var payments = ledger.paymentsKnownBefore(scope, accountId, from, to, knownBefore);
        ledger.replaceObservations(scope, accountId,
                DailyHistoryCalculator.calculate(from, to, payments, anchors, ranges), dataVersion);
    }

    private CompanyScope readAccount(UUID userId, UUID companyId, UUID accountId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        return scope;
    }

    private CompanyScope writeAccount(UUID userId, UUID companyId, UUID accountId) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        return scope;
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw ApiException.unprocessable("INVALID_RANGE", "Период задан неверно или длиннее 10 лет");
        }
    }
}
