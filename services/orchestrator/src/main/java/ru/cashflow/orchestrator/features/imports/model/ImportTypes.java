package ru.cashflow.orchestrator.features.imports.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;

public final class ImportTypes {

    private ImportTypes() {
    }

    /** Public contract statuses; CANCELLED was renamed to ABORTED and FINALIZED to COMMITTED. */
    public enum ImportStatus { STAGING, COMMITTED, REJECTED, ABORTED }

    public enum ValidationMode { ATOMIC, SKIP_INVALID }

    public enum ImportCompleteness { UNKNOWN, CONFIRMED_COMPLETE }

    /** Upload metadata is validated once and persisted with the staged import. */
    public record ImportOptions(String fileName, String fileSha256, ValidationMode validationMode,
                                Completeness completeness, LocalDate declaredFromDate, LocalDate declaredToDate,
                                int rowsTotal, int rowsRejected, int rowsDuplicate, long warningCount) {
    }

    public record ImportRecord(UUID id, UUID companyId, UUID accountId, ImportStatus status, String idempotencyKey,
                               String requestHash, String inputFingerprint, String schemaVersion, String rulesVersion,
                               String preprocessorStatus, String fileName, String fileSha256, int totalRows,
                               int acceptedRows, int rejectedRows, int duplicateRows, int warningCount,
                               LocalDate fromDate, LocalDate toDate, ValidationMode validationMode,
                               ImportCompleteness completeness, int rowsStaged, Integer rowsInserted,
                               Integer rowsAlreadyPresent, long issueCount, UUID createdBy, Instant createdAt,
                               UUID finalizedBy, Instant finalizedAt, Long dataVersion, long version) {
    }

    public record StagedPayment(int rowNo, String externalOperationId, Integer sourceRow, LocalDate operationDate,
                                BigDecimal amount, Direction direction, String counterpartyInn,
                                String counterpartyName, String purpose, String categoryCode,
                                CategorySource categorySource, String ruleVersion, BigDecimal balanceEod) {
    }

    public record StagedBalance(LocalDate date, BigDecimal balance) {
    }

    public record StagedIssue(int seq, String code, String severity, Long sourceLine, String field, String message) {
    }

    /** Validated, account-bound content of one PreProcessor result, ready to be staged. */
    public record StagedImport(String inputFingerprint, String schemaVersion, String rulesVersion,
                               String preprocessorStatus, long issueCount, Integer totalRows, Integer acceptedRows,
                               Integer rejectedRows, Integer duplicateRows, int warningCount,
                               List<StagedPayment> payments, List<StagedBalance> balances,
                               List<StagedIssue> issues) {
    }
}
