package ru.cashflow.orchestrator.features.imports.application;

import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.application.CompanyService;
import ru.cashflow.orchestrator.features.companies.model.Account;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.imports.model.RawOperation;
import ru.cashflow.orchestrator.features.ledger.application.LedgerService;
import ru.cashflow.orchestrator.features.ledger.command.CounterpartyKeys;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.AnchorSource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;

/**
 * Atomic all-companies import for the organizers' source export.
 * Company IDs are resolved only within the authenticated owner's catalog.
 */
@Service
public class RawOperationsImportService {

    /**
     * The export carries no categories, so every row is OTHER. A non-null rule version is still required: forecast
     * calibration only trusts category revisions whose rule version is known, and without it every calibration origin
     * is rejected (SCORING_RULES_UNAVAILABLE), which turns the cash-gap risk off for all imported companies.
     */
    static final String RAW_RULE_VERSION = "RAW-EXPORT-1";

    private final RawOperationsCsvParser parser;
    private final CompanyRepository companies;
    private final CompanyService companyService;
    private final CompanyAccess access;
    private final LedgerRepository ledger;
    private final LedgerService ledgerService;
    private final JdbcClient jdbc;
    private final Clock clock;

    public RawOperationsImportService(RawOperationsCsvParser parser, CompanyRepository companies,
                                      CompanyService companyService, CompanyAccess access,
                                      LedgerRepository ledger, LedgerService ledgerService,
                                      JdbcClient jdbc, Clock clock) {
        this.parser = parser;
        this.companies = companies;
        this.companyService = companyService;
        this.access = access;
        this.ledger = ledger;
        this.ledgerService = ledgerService;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Result importFile(UUID userId, InputStream file) {
        List<CompanyResult> results = new ArrayList<>();
        parser.parse(file, (sourceId, operations) -> results.add(importGroup(userId, sourceId, operations)));
        return new Result(results.size(), results.stream().mapToInt(CompanyResult::inserted).sum(),
                results.stream().mapToInt(CompanyResult::existing).sum(), List.copyOf(results));
    }

    private CompanyResult importGroup(UUID userId, String externalCompanyId, List<RawOperation> operations) {
        var imported = companies.findImported(userId, externalCompanyId).orElseThrow(() ->
                ApiException.unprocessable("COMPANY_NOT_IMPORTED",
                        "Сначала загрузите каталог компаний: отсутствует company_id из CSV"));
        UUID companyId = imported.id();
        CompanyScope scope = access.requireWrite(userId, companyId);
        var company = companies.lockCompany(scope).orElseThrow(ApiException::notFound);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(company.timezone())));
        for (var operation : operations) {
            if (!operation.operationDate().isBefore(today)) {
                throw ApiException.unprocessable("OPEN_DAY_ROWS",
                        "Файл содержит текущие или будущие операции");
            }
        }

        List<Account> accounts = companies.listAccounts(scope).stream()
                .filter(a -> externalCompanyId.equals(a.externalCompanyId())).toList();
        if (accounts.size() > 1 || (accounts.size() == 1 && accounts.getFirst().archived())) {
            throw ApiException.conflict("AMBIGUOUS_ACCOUNT", "Неоднозначная привязка счёта из CSV");
        }
        UUID accountId = accounts.isEmpty()
                ? companyService.createAccount(userId, companyId, "Основной (импорт CSV)",
                        null, null, externalCompanyId).account().id()
                : accounts.getFirst().id();

        int inserted = 0;
        int existing = 0;
        Map<LocalDate, java.math.BigDecimal> balances = new HashMap<>();
        Set<LocalDate> insertedDates = new HashSet<>();
        for (var op : operations) {
            var previousBalance = balances.putIfAbsent(op.operationDate(), op.balanceEod());
            if (previousBalance != null && previousBalance.compareTo(op.balanceEod()) != 0) {
                throw ApiException.unprocessable("INCONSISTENT_EOD",
                        "Разные остатки на конец одного дня");
            }
            // An operation ID cannot silently change financial values during re-import.
            var known = jdbc.sql("select operation_date, amount, direction, counterparty_inn, "
                            + "counterparty_name, purpose, balance_eod from payments "
                            + "where company_id=:company and account_id=:account and external_operation_id=:external")
                    .param("company", companyId).param("account", accountId)
                    .param("external", op.externalOperationId())
                    .query((rs, n) -> new RawOperation(externalCompanyId, op.externalOperationId(),
                            rs.getObject("operation_date", LocalDate.class), rs.getBigDecimal("amount"),
                            ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction
                                    .valueOf(rs.getString("direction")),
                            rs.getString("counterparty_inn"), rs.getString("counterparty_name"),
                            rs.getString("purpose"), rs.getBigDecimal("balance_eod"))).optional();
            if (known.isPresent()) {
                if (!known.get().equals(op)) {
                    throw ApiException.conflict("OPERATION_ID_CONFLICT",
                            "Операция с тем же ID уже существует с другими данными");
                }
                existing++;
                continue;
            }
            var now = clock.instant();
            var knownAt = knownAtEndOfDay(op.operationDate(), company.timezone(), now);
            var payment = new Payment(UUID.randomUUID(), companyId, accountId, null, op.externalOperationId(),
                    null, op.operationDate(), op.amount(), op.direction(),
                    CounterpartyKeys.of(op.counterpartyInn(), op.counterpartyName()),
                    op.counterpartyInn(), op.counterpartyName(), op.purpose(), "OTHER", CategorySource.OTHER,
                    RAW_RULE_VERSION, op.balanceEod(), 0, knownAt, now);
            if (ledger.insertPaymentIfAbsent(payment)) {
                inserted++;
                insertedDates.add(op.operationDate());
            } else {
                throw ApiException.conflict("CONCURRENT_OPERATION_IMPORT",
                        "Конкурирующая загрузка операций; повторите запрос");
            }
        }

        if (inserted > 0) {
            var now = clock.instant();
            Set<LocalDate> anchored = new HashSet<>();
            ledger.anchorsKnownBefore(scope, accountId, now.plusNanos(1)).forEach(a -> anchored.add(a.date()));
            for (var balance : balances.entrySet()) {
                if (insertedDates.contains(balance.getKey()) && anchored.add(balance.getKey())) {
                    ledger.insertAnchor(scope, new BalanceAnchor(UUID.randomUUID(), accountId,
                            balance.getKey(), balance.getValue(), AnchorSource.STATEMENT,
                            "CSV операций", knownAtEndOfDay(balance.getKey(), company.timezone(), now), userId,
                            null), null);
                }
            }
            ledgerService.carryBalanceOverEmptyDays(scope, accountId, userId, balances, anchored, null,
                    company.timezone(), now, "CSV операций: операций нет, остаток перенесён", null);
            confirmImportedPeriod(scope, accountId, userId, now, company.timezone());
            long version = companies.incrementDataVersion(scope, now);
            ledgerService.rebuildProjection(scope, accountId, version);
        }
        return new CompanyResult(externalCompanyId, companyId, accountId, inserted, existing);
    }

    /**
     * The organizers' export is treated as a complete statement: a day without rows between the first and the
     * last operation of the account is a confirmed zero, not a gap. Without this every day stays MISSING and
     * forecasts fail with INSUFFICIENT_HISTORY. Skipped when an equal or wider confirmation already exists.
     */
    private void confirmImportedPeriod(CompanyScope scope, UUID accountId, UUID userId, java.time.Instant now,
                                       String timezone) {
        var bounds = ledger.paymentDateRange(scope, accountId);
        if (bounds.isEmpty()) {
            return;
        }
        LocalDate from = bounds.get()[0];
        LocalDate to = bounds.get()[1];
        boolean covered = ledger.completenessKnownBefore(scope, accountId, now.plusNanos(1)).stream()
                .anyMatch(r -> r.completeness() == Completeness.CONFIRMED_COMPLETE
                        && !r.fromDate().isAfter(from) && !r.toDate().isBefore(to));
        if (!covered) {
            ledger.insertCompleteness(scope, new CompletenessRange(UUID.randomUUID(), accountId, from, to,
                    Completeness.CONFIRMED_COMPLETE, "Импорт CSV операций: выгрузка считается полной",
                    knownAtEndOfDay(from, timezone, now), userId));
        }
    }

    private static java.time.Instant knownAtEndOfDay(LocalDate day, String timezone, java.time.Instant now) {
        return ru.cashflow.orchestrator.features.ledger.command.ArchiveKnowledge.knownAtEndOfDay(day, timezone, now);
    }

    public record CompanyResult(String externalCompanyId, UUID companyId, UUID accountId, int inserted,
                                int existing) {
    }

    public record Result(int companies, int inserted, int existing, List<CompanyResult> items) {
    }
}
