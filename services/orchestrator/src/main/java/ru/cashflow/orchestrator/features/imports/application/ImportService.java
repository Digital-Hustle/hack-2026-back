package ru.cashflow.orchestrator.features.imports.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.Account;
import ru.cashflow.orchestrator.features.companies.model.Company;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ImportCompleteness;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ImportRecord;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ImportStatus;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ImportOptions;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedBalance;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedImport;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedIssue;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedPayment;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ValidationMode;
import ru.cashflow.orchestrator.features.imports.repository.ImportRepository;
import ru.cashflow.orchestrator.features.ledger.application.LedgerService;
import ru.cashflow.orchestrator.features.ledger.command.CounterpartyKeys;
import ru.cashflow.orchestrator.features.ledger.command.DailyHistoryCalculator;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.AnchorSource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.features.paging.model.PageQuery;
import ru.cashflow.orchestrator.features.paging.model.PageResult;

/**
 * Two-step import of a PreProcessor result: stage (nothing visible to history/forecast), then an atomic,
 * idempotent finalize. Re-finalizing or re-importing the same statement never creates a fact twice because
 * (company, account, external_operation_id) is unique for payments.
 */
@Service
public class ImportService {

    private static final Logger LOG = LoggerFactory.getLogger(ImportService.class);

    private final ImportRepository imports;
    private final LedgerRepository ledger;
    private final LedgerService ledgerService;
    private final CompanyRepository companies;
    private final CompanyAccess access;
    private final Clock clock;

    public ImportService(ImportRepository imports, LedgerRepository ledger, LedgerService ledgerService,
                         CompanyRepository companies, CompanyAccess access, Clock clock) {
        this.imports = imports;
        this.ledger = ledger;
        this.ledgerService = ledgerService;
        this.companies = companies;
        this.access = access;
        this.clock = clock;
    }

    /**
     * Same Idempotency-Key and same body returns the existing import; same key with another body is 409.
     * The request hash is computed by the caller over the canonical payload, not over the raw HTTP bytes.
     * ATOMIC imports with ERROR rows are persisted as REJECTED and the controller returns 422 with their id.
     */
    @Transactional
    public ImportRecord stage(UUID userId, UUID companyId, UUID accountId, String idempotencyKey,
                              ImportOptions options, Function<Account, StagedImport> mapper, String requestHash) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        Account account = companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        if (account.archived()) {
            throw ApiException.forbidden();
        }
        validateKey(idempotencyKey);
        companies.lockCompany(scope).orElseThrow(ApiException::notFound);
        Optional<ImportRecord> existing = imports.findByKey(scope, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), accountId, requestHash);
        }
        StagedImport staged = warnManualDuplicates(scope, accountId, mapper.apply(account));
        LocalDate today = today(scope);
        validateOptions(options, staged, today);
        if (staged.payments().stream().anyMatch(p -> !p.operationDate().isBefore(today))
                || staged.balances().stream().anyMatch(b -> !b.date().isBefore(today))) {
            throw ApiException.unprocessable("OPEN_DAY_ROWS",
                    "Выписка содержит текущий или будущий день; импортируются только закрытые дни");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        imports.insert(scope, id, accountId, idempotencyKey, requestHash, staged,
                options.fileName() == null ? "statement.csv" : options.fileName(),
                options.fileSha256() == null ? staged.inputFingerprint() : options.fileSha256(),
                options.validationMode(), ImportCompleteness.valueOf(options.completeness().name()),
                fromDate(staged, options), toDate(staged, options), userId, now);
        if (rejected(staged, options)) {
            imports.markRejected(id, userId, now);
            LOG.info("import_rejected rows={} issues={}", staged.payments().size(), staged.issueCount());
        } else {
            LOG.info("import_staged rows={} issues={}", staged.payments().size(), staged.issueCount());
        }
        return imports.find(scope, accountId, id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public ImportRecord finalizeImport(UUID userId, UUID companyId, UUID accountId, UUID importId, long version) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        Account account = companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        if (account.archived()) {
            throw ApiException.forbidden();
        }
        ImportRecord record = imports.lock(scope, accountId, importId).orElseThrow(ApiException::notFound);
        if (record.status() == ImportStatus.COMMITTED) {
            return record;
        }
        if (record.status() != ImportStatus.STAGING) {
            throw ApiException.conflict("IMPORT_CLOSED", "Импорт отклонён или отменён и не может быть завершён");
        }
        if (record.version() != version) {
            throw ApiException.conflict("VERSION_CONFLICT", "Импорт изменён другим запросом; обновите данные");
        }
        companies.lockCompany(scope).orElseThrow(ApiException::notFound);
        Instant now = clock.instant();
        List<StagedPayment> rows = imports.stagedPayments(importId);
        int inserted = 0;
        Set<LocalDate> newlyInsertedDates = new HashSet<>();
        for (StagedPayment row : rows) {
            Payment payment = new Payment(UUID.randomUUID(), scope.companyId(), accountId, importId,
                    row.externalOperationId(), row.sourceRow(), row.operationDate(), row.amount(), row.direction(),
                    CounterpartyKeys.of(row.counterpartyInn(), row.counterpartyName()), row.counterpartyInn(),
                    row.counterpartyName(), row.purpose(), row.categoryCode(), row.categorySource(),
                    row.ruleVersion(), row.balanceEod(), 0, now, now);
            if (ledger.insertPaymentIfAbsent(payment)) {
                inserted++;
                newlyInsertedDates.add(row.operationDate());
            }
        }
        // Reusing an existing payment must not replay its bank EOD anchor (especially over a
        // manually corrected one). An anchor is created only for a new payment date without any
        // prior anchor; corrections belong to the explicit manual-anchor API.
        Set<LocalDate> anchoredDates = new HashSet<>();
        if (!newlyInsertedDates.isEmpty()) {
            ledger.anchorsKnownBefore(scope, accountId, now.plusNanos(1)).stream()
                    .map(BalanceAnchor::date).forEach(anchoredDates::add);
        }
        boolean anchorsChanged = false;
        for (StagedBalance balance : imports.stagedBalances(importId)) {
            if (newlyInsertedDates.contains(balance.date()) && anchoredDates.add(balance.date())) {
                ledger.insertAnchor(scope, new BalanceAnchor(UUID.randomUUID(), accountId, balance.date(),
                        balance.balance(), AnchorSource.STATEMENT, "Выписка", now, userId, null), importId);
                anchorsChanged = true;
            }
        }

        // Confirmation is independent of inserted rows: the same CSV can first be imported
        // with UNKNOWN coverage and then confirmed by the user under another import key.
        boolean completenessChanged = record.completeness() == ImportCompleteness.CONFIRMED_COMPLETE
                && !DailyHistoryCalculator.isFullyConfirmed(record.fromDate(), record.toDate(),
                        ledger.completenessKnownBefore(scope, accountId, now.plusNanos(1)));
        if (completenessChanged) {
            ledger.insertCompleteness(scope, new CompletenessRange(UUID.randomUUID(), accountId,
                    record.fromDate(), record.toDate(), Completeness.CONFIRMED_COMPLETE,
                    "Подтверждённый период импортированной выписки", now, userId));
        }

        boolean financialInputsChanged = inserted > 0 || anchorsChanged || completenessChanged;
        long dataVersion;
        if (financialInputsChanged) {
            dataVersion = companies.incrementDataVersion(scope, now);
            ledgerService.rebuildProjection(scope, accountId, dataVersion);
        } else {
            dataVersion = companies.findCompany(scope).orElseThrow(ApiException::notFound).dataVersion();
        }
        imports.markFinalized(importId, inserted, rows.size() - inserted, dataVersion, userId, now);
        LOG.info("import_finalized inserted={} alreadyPresent={}", inserted, rows.size() - inserted);
        return imports.find(scope, accountId, importId).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public ImportRecord cancel(UUID userId, UUID companyId, UUID accountId, UUID importId) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        ImportRecord record = imports.lock(scope, accountId, importId).orElseThrow(ApiException::notFound);
        if (record.status() == ImportStatus.COMMITTED) {
            throw ApiException.conflict("IMPORT_CLOSED", "Завершённый импорт нельзя отменить");
        }
        if (record.status() == ImportStatus.REJECTED) {
            throw ApiException.conflict("IMPORT_CLOSED", "Отклонённый импорт нельзя отменить");
        }
        if (record.status() == ImportStatus.STAGING) {
            imports.markCancelled(importId, userId, clock.instant());
        }
        return imports.find(scope, accountId, importId).orElseThrow(ApiException::notFound);
    }

    public ImportRecord get(UUID userId, UUID companyId, UUID accountId, UUID importId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        return imports.find(scope, accountId, importId).orElseThrow(ApiException::notFound);
    }

    public ImportRecord get(UUID userId, UUID companyId, UUID importId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        return imports.find(scope, importId).orElseThrow(ApiException::notFound);
    }

    public PageResult<StagedIssue> issues(UUID userId, UUID companyId, UUID accountId, UUID importId,
                                          String severity, PageQuery page) {
        CompanyScope scope = access.requireRead(userId, companyId);
        imports.find(scope, accountId, importId).orElseThrow(ApiException::notFound);
        return issues(importId, severity, page);
    }

    public PageResult<StagedIssue> issues(UUID userId, UUID companyId, UUID importId, String severity,
                                          PageQuery page) {
        CompanyScope scope = access.requireRead(userId, companyId);
        imports.find(scope, importId).orElseThrow(ApiException::notFound);
        return issues(importId, severity, page);
    }

    public PageResult<ImportRecord> list(UUID userId, UUID companyId, UUID accountId, PageQuery page) {
        CompanyScope scope = access.requireRead(userId, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        return PageResult.of(imports.list(scope, accountId, page.offset(), page.size()),
                imports.count(scope, accountId));
    }

    public static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private PageResult<StagedIssue> issues(UUID importId, String severity, PageQuery page) {
        String normalized = normalizeSeverity(severity);
        return PageResult.of(imports.issues(importId, normalized, page.offset(), page.size()),
                imports.countIssues(importId, normalized));
    }

    private static String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return null;
        }
        String value = severity.strip().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "INFO", "WARNING", "ERROR" -> value;
            default -> throw ApiException.unprocessable("INVALID_SEVERITY", "severity: INFO|WARNING|ERROR");
        };
    }

    private static boolean rejected(StagedImport staged, ImportOptions options) {
        return "INVALID".equals(staged.preprocessorStatus())
                || options.validationMode() == ValidationMode.ATOMIC
                && (options.rowsRejected() > 0 || !"PREPARED".equals(staged.preprocessorStatus())
                || staged.issues().stream().anyMatch(issue -> "ERROR".equals(issue.severity())));
    }

    private static LocalDate fromDate(StagedImport staged, ImportOptions options) {
        if (options.declaredFromDate() != null) {
            return options.declaredFromDate();
        }
        return staged.payments().stream().map(StagedPayment::operationDate).min(LocalDate::compareTo).orElse(null);
    }

    private static LocalDate toDate(StagedImport staged, ImportOptions options) {
        if (options.declaredToDate() != null) {
            return options.declaredToDate();
        }
        return staged.payments().stream().map(StagedPayment::operationDate).max(LocalDate::compareTo).orElse(null);
    }

    private ImportRecord replay(ImportRecord existing, UUID accountId, String requestHash) {
        if (!existing.accountId().equals(accountId) || !existing.requestHash().equals(requestHash)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "Ключ идемпотентности уже использован с другим запросом");
        }
        return existing;
    }

    private LocalDate today(CompanyScope scope) {
        Company company = companies.findCompany(scope).orElseThrow(ApiException::notFound);
        return LocalDate.now(clock.withZone(ZoneId.of(company.timezone())));
    }

    /**
     * A manual fact and an imported operation are different identities, so the unique external-id index cannot
     * catch a re-entry of the same cash movement. This only warns; the user's manual fact is never deleted.
     */
    private StagedImport warnManualDuplicates(CompanyScope scope, UUID accountId, StagedImport staged) {
        if (staged.payments().isEmpty()) {
            return staged;
        }
        LocalDate from = staged.payments().stream().map(StagedPayment::operationDate).min(LocalDate::compareTo)
                .orElseThrow();
        LocalDate to = staged.payments().stream().map(StagedPayment::operationDate).max(LocalDate::compareTo)
                .orElseThrow();
        List<Payment> manual = ledger.manualPaymentsBetween(scope, accountId, from, to);
        if (manual.isEmpty()) {
            return staged;
        }
        List<StagedIssue> issues = new ArrayList<>(staged.issues());
        int seq = issues.stream().mapToInt(StagedIssue::seq).max().orElse(0);
        int added = 0;
        for (StagedPayment row : staged.payments()) {
            if (manual.stream().anyMatch(candidate -> sameMovement(candidate, row))) {
                seq++;
                issues.add(new StagedIssue(seq, "POSSIBLE_DUPLICATE_MANUAL", "WARNING", (long) row.rowNo(),
                        "operation_id", "Похожий ручной платёж уже есть на эту дату; проверьте, не дубль"));
                added++;
            }
        }
        if (added == 0) {
            return staged;
        }
        return new StagedImport(staged.inputFingerprint(), staged.schemaVersion(), staged.rulesVersion(),
                staged.preprocessorStatus(), staged.issueCount() + added, staged.totalRows(), staged.acceptedRows(),
                staged.rejectedRows(), staged.duplicateRows(), staged.warningCount() + added, staged.payments(),
                staged.balances(), List.copyOf(issues));
    }

    private static boolean sameMovement(Payment manual, StagedPayment row) {
        if (!manual.operationDate().equals(row.operationDate()) || manual.direction() != row.direction()
                || manual.amount().compareTo(row.amount()) != 0) {
            return false;
        }
        String rowKey = CounterpartyKeys.of(row.counterpartyInn(), row.counterpartyName());
        if (manual.counterpartyKey() != null && rowKey != null) {
            return manual.counterpartyKey().equals(rowKey);
        }
        if (manual.counterpartyInn() != null && row.counterpartyInn() != null) {
            return manual.counterpartyInn().equals(row.counterpartyInn());
        }
        return manual.counterpartyName() != null && row.counterpartyName() != null
                && manual.counterpartyName().equalsIgnoreCase(row.counterpartyName());
    }

    private static List<LocalDate[]> gaps(List<StagedPayment> rows) {
        List<LocalDate> dates = rows.stream().map(StagedPayment::operationDate).distinct().sorted().toList();
        List<LocalDate[]> gaps = new java.util.ArrayList<>();
        for (int i = 1; i < dates.size(); i++) {
            if (java.time.temporal.ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i)) > 7) {
                gaps.add(new LocalDate[] {dates.get(i - 1).plusDays(1), dates.get(i).minusDays(1)});
            }
        }
        return gaps;
    }

    private static void validateOptions(ImportOptions options, StagedImport staged, LocalDate today) {
        if (options == null || options.validationMode() == null || options.completeness() == null) {
            throw ApiException.unprocessable("INVALID_IMPORT_OPTIONS", "Не заданы параметры импорта");
        }
        if (options.fileName() != null && (options.fileName().isBlank() || options.fileName().length() > 200
                || options.fileName().chars().anyMatch(c -> c < 32))) {
            throw ApiException.unprocessable("INVALID_FILE_NAME", "Некорректное имя файла");
        }
        if (options.fileSha256() != null && !options.fileSha256().matches("^[a-f0-9]{64}$")) {
            throw ApiException.unprocessable("INVALID_FILE_SHA256", "Некорректный SHA-256 файла");
        }
        if (options.rowsTotal() < 0 || options.rowsRejected() < 0 || options.rowsDuplicate() < 0
                || options.warningCount() < 0) {
            throw ApiException.unprocessable("INVALID_IMPORT_COUNTS", "Отрицательные счётчики строк");
        }
        if (!rejected(staged, options) && options.fileSha256() != null
                && (options.rowsTotal() != staged.payments().size()
                + options.rowsRejected() + options.rowsDuplicate())) {
            throw ApiException.unprocessable("INVALID_IMPORT_COUNTS", "Счётчики PreProcessor не согласованы");
        }
        if (options.completeness() == Completeness.UNKNOWN) {
            if (options.declaredFromDate() != null || options.declaredToDate() != null) {
                throw ApiException.unprocessable("INVALID_COMPLETENESS_RANGE", "UNKNOWN не принимает диапазон дат");
            }
        } else if (options.completeness() == Completeness.CONFIRMED_COMPLETE) {
            if (!rejected(staged, options)
                    && (options.rowsRejected() > 0 || !"PREPARED".equals(staged.preprocessorStatus()))) {
                throw ApiException.unprocessable("INCOMPLETE_STATEMENT",
                        "Нельзя подтвердить полноту выписки с отклонёнными строками; исправьте файл или выберите UNKNOWN");
            }
            LocalDate from = options.declaredFromDate();
            LocalDate to = options.declaredToDate();
            if (from == null || to == null || from.isAfter(to) || !to.isBefore(today)) {
                throw ApiException.unprocessable("INVALID_COMPLETENESS_RANGE", "Неверный период полноты истории");
            }
            if (staged.payments().stream().anyMatch(p -> p.operationDate().isBefore(from)
                    || p.operationDate().isAfter(to))) {
                throw ApiException.unprocessable("INVALID_COMPLETENESS_RANGE",
                        "Операции выписки выходят за подтверждённый период");
            }
        }
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("^[A-Za-z0-9_.:-]{8,128}$")) {
            throw new ApiException(400, "BAD_IDEMPOTENCY_KEY", "Idempotency-Key: 8..128 символов [A-Za-z0-9_.:-]");
        }
    }
}
