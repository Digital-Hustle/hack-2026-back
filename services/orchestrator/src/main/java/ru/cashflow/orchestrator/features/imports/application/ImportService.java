package ru.cashflow.orchestrator.features.imports.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ImportRecord;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ImportStatus;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedBalance;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedImport;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedIssue;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedPayment;
import ru.cashflow.orchestrator.features.imports.repository.ImportRepository;
import ru.cashflow.orchestrator.features.ledger.application.LedgerService;
import ru.cashflow.orchestrator.features.ledger.command.CounterpartyKeys;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.AnchorSource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;

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
     */
    @Transactional
    public ImportRecord stage(UUID userId, UUID companyId, UUID accountId, String idempotencyKey,
                              Function<Account, StagedImport> mapper, String requestHash) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        Account account = companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        validateKey(idempotencyKey);
        Optional<ImportRecord> existing = imports.findByKey(scope, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), accountId, requestHash);
        }
        StagedImport staged = mapper.apply(account);
        LocalDate today = today(scope);
        if (staged.payments().stream().anyMatch(p -> !p.operationDate().isBefore(today))
                || staged.balances().stream().anyMatch(b -> !b.date().isBefore(today))) {
            throw ApiException.unprocessable("OPEN_DAY_ROWS",
                    "Выписка содержит текущий или будущий день; импортируются только закрытые дни");
        }
        UUID id = UUID.randomUUID();
        imports.insert(scope, id, accountId, idempotencyKey, requestHash, staged, userId, clock.instant());
        LOG.info("import_staged rows={} issues={}", staged.payments().size(), staged.issueCount());
        return imports.find(scope, accountId, id).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public ImportRecord finalizeImport(UUID userId, UUID companyId, UUID accountId, UUID importId, long version) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        ImportRecord record = imports.lock(scope, accountId, importId).orElseThrow(ApiException::notFound);
        if (record.status() == ImportStatus.FINALIZED) {
            return record;
        }
        if (record.status() != ImportStatus.STAGED) {
            throw ApiException.conflict("IMPORT_CLOSED", "Импорт отменён и не может быть завершён");
        }
        if (record.version() != version) {
            throw ApiException.conflict("VERSION_CONFLICT", "Импорт изменён другим запросом; обновите данные");
        }
        companies.lockCompany(scope).orElseThrow(ApiException::notFound);
        Instant now = clock.instant();
        List<StagedPayment> rows = imports.stagedPayments(importId);
        int inserted = 0;
        for (StagedPayment row : rows) {
            Payment payment = new Payment(UUID.randomUUID(), scope.companyId(), accountId, importId,
                    row.externalOperationId(), row.sourceRow(), row.operationDate(), row.amount(), row.direction(),
                    CounterpartyKeys.of(row.counterpartyInn(), row.counterpartyName()), row.counterpartyInn(),
                    row.counterpartyName(), row.purpose(), row.categoryCode(), row.categorySource(),
                    row.ruleVersion(), row.balanceEod(), 0, now, now);
            if (ledger.insertPaymentIfAbsent(payment)) {
                inserted++;
            }
        }
        for (StagedBalance balance : imports.stagedBalances(importId)) {
            ledger.insertAnchor(scope, new BalanceAnchor(UUID.randomUUID(), accountId, balance.date(),
                    balance.balance(), AnchorSource.STATEMENT, "Выписка", now, userId, null), importId);
        }
        if (!rows.isEmpty()) {
            LocalDate from = rows.stream().map(StagedPayment::operationDate).min(LocalDate::compareTo).orElseThrow();
            LocalDate to = rows.stream().map(StagedPayment::operationDate).max(LocalDate::compareTo).orElseThrow();
            // The PreProcessor flags gaps longer than 7 days; such periods are not declared complete here.
            ledger.insertCompleteness(scope, new CompletenessRange(UUID.randomUUID(), accountId, from, to,
                    Completeness.CONFIRMED_COMPLETE, "Импорт выписки", now, userId));
            for (LocalDate[] gap : gaps(rows)) {
                ledger.insertCompleteness(scope, new CompletenessRange(UUID.randomUUID(), accountId, gap[0], gap[1],
                        Completeness.UNKNOWN, "Пропуск более 7 дней в выписке", now.plusNanos(1000), userId));
            }
        }
        long dataVersion = companies.incrementDataVersion(scope, now);
        ledgerService.rebuildProjection(scope, accountId, dataVersion);
        imports.markFinalized(importId, inserted, rows.size() - inserted, userId, now);
        LOG.info("import_finalized inserted={} alreadyPresent={}", inserted, rows.size() - inserted);
        return imports.find(scope, accountId, importId).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public ImportRecord cancel(UUID userId, UUID companyId, UUID accountId, UUID importId) {
        CompanyScope scope = access.requireWrite(userId, companyId);
        ImportRecord record = imports.lock(scope, accountId, importId).orElseThrow(ApiException::notFound);
        if (record.status() == ImportStatus.FINALIZED) {
            throw ApiException.conflict("IMPORT_CLOSED", "Завершённый импорт нельзя отменить");
        }
        if (record.status() == ImportStatus.STAGED) {
            imports.markCancelled(importId, userId, clock.instant());
        }
        return imports.find(scope, accountId, importId).orElseThrow(ApiException::notFound);
    }

    public ImportRecord get(UUID userId, UUID companyId, UUID accountId, UUID importId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        return imports.find(scope, accountId, importId).orElseThrow(ApiException::notFound);
    }

    public List<StagedIssue> issues(UUID userId, UUID companyId, UUID accountId, UUID importId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        imports.find(scope, accountId, importId).orElseThrow(ApiException::notFound);
        return imports.issues(importId);
    }

    public List<ImportRecord> list(UUID userId, UUID companyId, UUID accountId) {
        CompanyScope scope = access.requireRead(userId, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        return imports.list(scope, accountId);
    }

    public static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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

    private static void validateKey(String key) {
        if (key == null || !key.matches("^[A-Za-z0-9_.:-]{8,128}$")) {
            throw new ApiException(400, "BAD_IDEMPOTENCY_KEY", "Idempotency-Key: 8..128 символов [A-Za-z0-9_.:-]");
        }
    }
}
