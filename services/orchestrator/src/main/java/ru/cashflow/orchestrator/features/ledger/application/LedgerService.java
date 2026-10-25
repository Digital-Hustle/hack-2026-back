package ru.cashflow.orchestrator.features.ledger.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.Account;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.jobs.repository.IdempotencyRepository;
import ru.cashflow.orchestrator.features.ledger.command.DailyHistoryCalculator;
import ru.cashflow.orchestrator.features.ledger.command.DataQualityCalculator;
import ru.cashflow.orchestrator.features.ledger.model.CategoryCode;
import ru.cashflow.orchestrator.features.ledger.model.Counterparty;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.AnchorSource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategoryChange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategoryRevision;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.DailyObservation;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.PaymentView;
import ru.cashflow.orchestrator.features.ledger.model.PaymentFilter;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.features.paging.model.PageQuery;
import ru.cashflow.orchestrator.features.paging.model.PageResult;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;

@Service
public class LedgerService {

    private static final long MAX_RANGE_DAYS = 3660;
    private static final int MAX_BULK_PAYMENTS = 200;
    private static final int QUALITY_WINDOW_DAYS = 26 * 7;
    private static final int MAX_QUALITY_WINDOW_DAYS = 366;
    private static final String OPERATION_BULK_CATEGORY = "BULK_CATEGORY_CHANGE";

    private final LedgerRepository ledger;
    private final CompanyRepository companies;
    private final CompanyAccess access;
    private final IdempotencyRepository idempotency;
    private final Clock clock;

    public LedgerService(LedgerRepository ledger, CompanyRepository companies, CompanyAccess access,
                         IdempotencyRepository idempotency, Clock clock) {
        this.ledger = ledger;
        this.companies = companies;
        this.access = access;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public PageResult<PaymentView> payments(UUID userId, UUID companyId, UUID accountId, PaymentFilter filter,
                                            PageQuery page) {
        CompanyScope scope = readAccount(userId, companyId, accountId);
        validateFilter(filter);
        return ledger.payments(scope, accountId, filter, page);
    }

    public PaymentView payment(UUID userId, UUID companyId, UUID paymentId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        Payment payment = ledger.findPaymentForCompany(scope, paymentId).orElseThrow(ApiException::notFound);
        return ledger.findPaymentView(scope, payment.accountId(), paymentId).orElseThrow(ApiException::notFound);
    }

    public PageResult<Counterparty> counterpartyPage(UUID userId, UUID companyId, UUID accountId, String q,
                                                     PageQuery page) {
        CompanyScope scope = readAccount(userId, companyId, accountId);
        if (q != null && q.strip().length() > 200) {
            throw ApiException.unprocessable("INVALID_QUERY", "q: до 200 символов");
        }
        return ledger.counterparties(scope, accountId, q, page);
    }

    public List<CategoryCode> categories(UUID userId, UUID companyId) {
        access.requireRead(userId, companyId);
        return List.of(CategoryCode.values());
    }

    public List<CategoryRevision> revisions(UUID userId, UUID companyId, UUID accountId, UUID paymentId) {
        CompanyScope scope = readAccount(userId, companyId, accountId);
        ledger.findPayment(scope, accountId, paymentId).orElseThrow(ApiException::notFound);
        return ledger.revisions(scope, paymentId);
    }

    /** A user correction appends a revision; the immutable fact (date, amount, direction) is never rewritten. */
    @Transactional
    public PaymentView changeCategory(UUID userId, UUID companyId, UUID accountId, UUID paymentId, long version,
                                      String categoryCode) {
        CompanyScope scope = writeAccount(userId, companyId, accountId);
        Payment payment = ledger.lockPayment(scope, accountId, paymentId).orElseThrow(ApiException::notFound);
        return change(scope, payment, version, categoryCode);
    }

    /** Contract path scoped by company: the account is derived from the payment, never from the request. */
    @Transactional
    public PaymentView changeCategoryByCompany(UUID userId, UUID companyId, UUID paymentId, long version,
                                               String categoryCode) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        companies.lockCompany(scope).orElseThrow(ApiException::notFound);
        Payment payment = ledger.lockPaymentForCompany(scope, paymentId).orElseThrow(ApiException::notFound);
        return change(scope, payment, version, categoryCode);
    }

    /**
     * Atomic correction of up to 200 payments. The whole batch commits or rolls back, versions are checked under
     * row locks, and Idempotency-Key makes a retry return the same rows instead of applying the change twice.
     */
    @Transactional
    public List<PaymentView> changeCategories(UUID userId, UUID companyId, String categoryCode,
                                             List<CategoryChange> changes, String idempotencyKey) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        companies.lockCompany(scope).orElseThrow(ApiException::notFound);
        validateKey(idempotencyKey);
        if (changes.isEmpty() || changes.size() > MAX_BULK_PAYMENTS) {
            throw ApiException.unprocessable("INVALID_BATCH", "1..200 операций в одном запросе");
        }
        Set<UUID> unique = new HashSet<>();
        for (CategoryChange change : changes) {
            if (!unique.add(change.paymentId())) {
                throw ApiException.unprocessable("DUPLICATE_PAYMENT", "Операции не должны повторяться");
            }
        }
        String requestHash = Fingerprint.sha256(categoryCode + "|" + changes.stream()
                .sorted(java.util.Comparator.comparing(
                        (CategoryChange change) -> change.paymentId().toString()))
                .map(change -> change.paymentId() + ":" + change.expectedVersion())
                .reduce("", (left, right) -> left + "," + right));
        Optional<IdempotencyRepository.Record> replay = idempotency.find(scope, OPERATION_BULK_CATEGORY,
                idempotencyKey);
        if (replay.isPresent()) {
            if (!replay.get().requestHash().equals(requestHash)) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "Ключ идемпотентности уже использован с другим запросом");
            }
            return orderedViews(scope, changes);
        }
        Instant now = clock.instant();
        for (CategoryChange change : changes) {
            Payment payment = ledger.lockPaymentForCompany(scope, change.paymentId())
                    .orElseThrow(ApiException::notFound);
            change(scope, payment, change.expectedVersion(), categoryCode, now);
        }
        companies.incrementDataVersion(scope, now);
        if (!idempotency.insert(scope, OPERATION_BULK_CATEGORY, idempotencyKey, requestHash, UUID.randomUUID(), now)) {
            throw ApiException.conflict("CONCURRENT_REQUEST", "Повторите запрос: он уже выполняется");
        }
        return orderedViews(scope, changes);
    }

    private PaymentView change(CompanyScope scope, Payment payment, long version, String categoryCode) {
        Instant now = clock.instant();
        PaymentView view = change(scope, payment, version, categoryCode, now);
        companies.incrementDataVersion(scope, now);
        return view;
    }

    private PaymentView change(CompanyScope scope, Payment payment, long version, String categoryCode, Instant now) {
        if (ledger.findPaymentView(scope, payment.accountId(), payment.id()).orElseThrow().voidedAt() != null) {
            throw ApiException.conflict("PAYMENT_VOIDED", "Платёж аннулирован");
        }
        long expected = storedVersion(version);
        if (payment.version() != expected) {
            throw ApiException.conflict("VERSION_CONFLICT", "Платёж изменён другим запросом; обновите данные");
        }
        if (!ledger.updateCategory(scope, payment.id(), expected, categoryCode, CategorySource.USER, null)) {
            throw ApiException.conflict("VERSION_CONFLICT", "Платёж изменён другим запросом; обновите данные");
        }
        ledger.insertRevision(scope.companyId(), new CategoryRevision(payment.id(), categoryCode, CategorySource.USER,
                null, now, scope.userId(), expected + 1));
        return ledger.findPaymentView(scope, payment.accountId(), payment.id()).orElseThrow(ApiException::notFound);
    }

    /** Payment responses expose storage version + 1; 0 stays a legacy alias for the first version. */
    private static long storedVersion(long wireVersion) {
        return wireVersion == 0 ? 0 : wireVersion - 1;
    }

    private List<PaymentView> orderedViews(CompanyScope scope, List<CategoryChange> changes) {
        List<PaymentView> views = ledger.paymentViews(scope, changes.stream().map(CategoryChange::paymentId).toList());
        if (views.size() != changes.size()) {
            throw ApiException.notFound();
        }
        return changes.stream()
                .map(change -> views.stream()
                        .filter(view -> view.payment().id().equals(change.paymentId()))
                        .findFirst()
                        .orElseThrow(ApiException::notFound))
                .toList();
    }

    /** Raw append-only completeness assertions, most recent first, not an effective-state projection. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<CompletenessRange> completenessRanges(UUID userId, UUID companyId, UUID accountId,
                                                              PageQuery page) {
        CompanyScope scope = readAccount(userId, companyId, accountId);
        return ledger.completenessPage(scope, accountId, page);
    }

    /** Rolling 26 weeks ending on the company's last closed day, or an explicit closed-day window. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DataQualityCalculator.Summary dataQuality(UUID userId, UUID companyId, UUID accountId,
                                                      LocalDate from, LocalDate to) {
        CompanyScope scope = readAccount(userId, companyId, accountId);
        String timezone = companies.findCompany(scope).orElseThrow(ApiException::notFound).timezone();
        LocalDate lastClosedDay = LocalDate.now(clock.withZone(ZoneId.of(timezone))).minusDays(1);
        if ((from == null) != (to == null)) {
            throw ApiException.unprocessable("INVALID_RANGE", "Укажите обе границы периода");
        }
        LocalDate end = to == null ? lastClosedDay : to;
        LocalDate start = from == null ? end.minusDays(QUALITY_WINDOW_DAYS - 1L) : from;
        if (start.isAfter(end) || end.isAfter(lastClosedDay)
                || java.time.temporal.ChronoUnit.DAYS.between(start, end) >= MAX_QUALITY_WINDOW_DAYS) {
            throw ApiException.unprocessable("INVALID_RANGE", "Только закрытые дни, период не длиннее 366 дней");
        }
        return DataQualityCalculator.summarize(start, end, ledger.observations(scope, accountId, start, end),
                ledger.emptyPurposeCount(scope, accountId, start, end));
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
        companies.lockCompany(scope).orElseThrow(ApiException::notFound);
        Account account = companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        if (account.archived()) {
            throw ApiException.forbidden();
        }
        return scope;
    }

    private static void validateFilter(PaymentFilter filter) {
        if (filter.fromDate() != null && filter.toDate() != null) {
            validateRange(filter.fromDate(), filter.toDate());
        }
        if (filter.q() != null && filter.q().length() > 200) {
            throw ApiException.unprocessable("INVALID_QUERY", "q: до 200 символов");
        }
        if (filter.categoryCode() != null && filter.categoryCode().length() > 64) {
            throw ApiException.unprocessable("INVALID_QUERY", "categoryCode: до 64 символов");
        }
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to) || java.time.temporal.ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw ApiException.unprocessable("INVALID_RANGE", "Период задан неверно или длиннее 10 лет");
        }
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("^[A-Za-z0-9_.:-]{8,128}$")) {
            throw new ApiException(400, "BAD_IDEMPOTENCY_KEY", "Idempotency-Key: 8..128 символов [A-Za-z0-9_.:-]");
        }
    }

    /**
     * A statement gives the end-of-day balance only for days with operations. Inside a confirmed-complete period a
     * day without operations ends with the previous balance; storing it gives every day a balance, which backtest
     * scoring requires. Days already anchored are left untouched. Returns the number of anchors added.
     */
    public int carryBalanceOverEmptyDays(CompanyScope scope, UUID accountId, UUID userId,
                                         java.util.Map<LocalDate, BigDecimal> eods, Set<LocalDate> anchored,
                                         LocalDate through, String timezone, Instant now, String reason,
                                         UUID importId) {
        var dates = new java.util.TreeSet<>(eods.keySet());
        if (dates.isEmpty()) {
            return 0;
        }
        LocalDate last = through == null || through.isBefore(dates.last()) ? dates.last() : through;
        int added = 0;
        LocalDate previous = null;
        for (LocalDate date = dates.first(); !date.isAfter(last); date = date.plusDays(1)) {
            if (dates.contains(date)) {
                previous = date;
                continue;
            }
            if (anchored.add(date)) {
                ledger.insertAnchor(scope, new BalanceAnchor(UUID.randomUUID(), accountId, date, eods.get(previous),
                        AnchorSource.STATEMENT, reason,
                        ru.cashflow.orchestrator.features.ledger.command.ArchiveKnowledge.knownAtEndOfDay(date,
                                timezone, now), userId, null), importId);
                added++;
            }
        }
        return added;
    }
}
