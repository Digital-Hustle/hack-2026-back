package ru.cashflow.orchestrator.features.imports.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;

public final class ImportTypes {

    private ImportTypes() {
    }

    public enum ImportStatus { STAGED, FINALIZED, CANCELLED }

    public record ImportRecord(UUID id, UUID companyId, UUID accountId, ImportStatus status, String idempotencyKey,
                               String requestHash, String inputFingerprint, String schemaVersion, String rulesVersion,
                               String preprocessorStatus, int rowsStaged, Integer rowsInserted,
                               Integer rowsAlreadyPresent, long issueCount, UUID createdBy, Instant createdAt,
                               UUID finalizedBy, Instant finalizedAt, long version) {
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
                               String preprocessorStatus, long issueCount, List<StagedPayment> payments,
                               List<StagedBalance> balances, List<StagedIssue> issues) {
    }
}
