package ru.cashflow.orchestrator.api.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import ru.cashflow.orchestrator.api.dto.ImportDtos.PreparedDay;
import ru.cashflow.orchestrator.api.dto.ImportDtos.PreparedIssue;
import ru.cashflow.orchestrator.api.dto.ImportDtos.PreparedOperation;
import ru.cashflow.orchestrator.api.dto.ImportDtos.PreparedStatementRq;
import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedBalance;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedImport;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedIssue;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedPayment;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;

/**
 * Converts one PreProcessor result into rows of ONE target account. The CSV company_id must equal the
 * account's externalCompanyId: the file never selects a company or grants access on its own (handoff §2).
 */
public final class PreparedStatementMapper {

    public static final String SUPPORTED_SCHEMA = "1";
    private static final Pattern MONEY = Pattern.compile("^-?[0-9]{1,17}\\.[0-9]{2}$");
    private static final int MAX_ISSUES = 1000;

    private PreparedStatementMapper() {
    }

    public static StagedImport toStaged(PreparedStatementRq rq, String externalCompanyId) {
        if (!SUPPORTED_SCHEMA.equals(rq.schemaVersion())) {
            throw ApiException.unprocessable("UNSUPPORTED_SCHEMA", "Неподдерживаемая версия формата PreProcessor");
        }
        if (rq.operations().isEmpty() && !"INVALID".equals(rq.report().status())) {
            throw ApiException.unprocessable("NO_VALID_OPERATIONS", "В подготовленной выписке нет корректных операций");
        }
        if (externalCompanyId == null) {
            throw ApiException.unprocessable("ACCOUNT_NOT_LINKED",
                    "У счёта не задан externalCompanyId для сопоставления с выпиской");
        }
        List<StagedPayment> payments = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int row = 0;
        for (PreparedOperation op : rq.operations()) {
            row++;
            if (!externalCompanyId.equals(op.companyId())) {
                throw ApiException.unprocessable("FOREIGN_COMPANY_ROWS",
                        "Выписка содержит операции другой компании; загрузите выписку одного счёта");
            }
            if (!ids.add(op.operationId())) {
                throw ApiException.unprocessable("DUPLICATE_OPERATION_ID", "В выписке повторяется operationId");
            }
            CategorySource source = "RULE".equals(op.category().source()) ? CategorySource.RULE : CategorySource.OTHER;
            payments.add(new StagedPayment(row, op.operationId(), sourceRow(op.sourceLine()), op.operationDate(),
                    positive(op.amount(), "operations[].amount"), Direction.valueOf(op.direction()),
                    blankToNull(op.counterpartyInn()), blankToNull(op.counterpartyName()),
                    blankToNull(op.paymentPurpose()), op.category().code(), source,
                    blankToNull(op.category().rulesVersion()), money(op.balanceEod(), "operations[].balanceEod")));
        }
        Map<java.time.LocalDate, StagedBalance> balances = new HashMap<>();
        for (PreparedDay day : rq.dailyBalances()) {
            if (!externalCompanyId.equals(day.companyId())) {
                throw ApiException.unprocessable("FOREIGN_COMPANY_ROWS",
                        "Выписка содержит остатки другой компании; загрузите выписку одного счёта");
            }
            BigDecimal eod = money(day.balanceEod(), "dailyBalances[].balanceEod");
            if ("OBSERVED".equals(day.balanceStatus()) && eod != null
                    && balances.putIfAbsent(day.date(), new StagedBalance(day.date(), eod)) != null) {
                throw ApiException.unprocessable("DUPLICATE_DAY", "В выписке повторяется дневной остаток");
            }
        }
        List<StagedIssue> issues = new ArrayList<>();
        List<PreparedIssue> source = rq.report().issues() == null ? List.of() : rq.report().issues();
        int warningCount = 0;
        for (int i = 0; i < Math.min(source.size(), MAX_ISSUES); i++) {
            PreparedIssue issue = source.get(i);
            String severity = normalizeSeverity(issue.severity());
            if ("WARNING".equals(severity) || "INFO".equals(severity)) {
                warningCount++;
            }
            issues.add(new StagedIssue(i + 1, truncate(issue.code(), 64, "UNKNOWN"), severity, issue.sourceLine(),
                    truncate(issue.field(), 64, null), truncate(issue.message(), 500, "")));
        }
        int rejected = rq.report().rowsRejected();
        int duplicates = rq.report().rowsDuplicate();
        int accepted = payments.size();
        int total = rq.report().rowsTotal() > 0 ? rq.report().rowsTotal() : accepted + rejected + duplicates;
        long issueCount = Math.max(rq.report().issueCount(), 0);
        warningCount = (int) Math.min(Integer.MAX_VALUE, rq.report().warningCount());
        return new StagedImport(rq.inputFingerprint(), rq.schemaVersion(), rq.rulesVersion(), rq.report().status(),
                issueCount, total, accepted, rejected, duplicates,
                warningCount, List.copyOf(payments),
                balances.values().stream().sorted(java.util.Comparator.comparing(StagedBalance::date)).toList(),
                List.copyOf(issues));
    }

    private static String normalizeSeverity(String value) {
        if ("INFO".equals(value) || "WARNING".equals(value) || "ERROR".equals(value)) {
            return value;
        }
        return "WARNING";
    }

    private static BigDecimal positive(String value, String field) {
        BigDecimal amount = money(value, field);
        if (amount == null || amount.signum() <= 0) {
            throw invalid(field);
        }
        return amount;
    }

    private static BigDecimal money(String value, String field) {
        if (value == null) {
            return null;
        }
        if (!MONEY.matcher(value).matches()) {
            throw invalid(field);
        }
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static Integer sourceRow(Long line) {
        return line == null || line < 1 || line > Integer.MAX_VALUE ? null : line.intValue();
    }

    private static ApiException invalid(String field) {
        return new ApiException(422, "INVALID_MONEY", "Некорректная сумма в поле " + field);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String value, int max, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
