package ru.cashflow.orchestrator.features.ledger.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Ledger value types. Money is always BigDecimal with scale 2; dates are business days, instants are UTC. */
public final class LedgerTypes {

    private LedgerTypes() {
    }

    public enum Direction { IN, OUT }

    public enum CategorySource { RULE, COUNTERPARTY, USER, OTHER }

    public enum DayStatus { OBSERVED, ZERO_CONFIRMED, MISSING }

    public enum Completeness { CONFIRMED_COMPLETE, UNKNOWN }

    public enum DataQualityLevel { GOOD, LIMITED, INSUFFICIENT }

    public enum AnchorSource { STATEMENT, MANUAL }

    /** Facts imported from a statement, entered by the owner, or (future) received from a bank connector. */
    public enum PaymentSource { IMPORT, MANUAL, BANK }

    public record Payment(UUID id, UUID companyId, UUID accountId, UUID importId, String externalOperationId,
                          Integer sourceRow, LocalDate operationDate, BigDecimal amount, Direction direction,
                          String counterpartyKey, String counterpartyInn, String counterpartyName, String purpose,
                          String categoryCode, CategorySource categorySource, String ruleVersion,
                          BigDecimal balanceEod, long version, Instant knownAt, Instant createdAt,
                          PaymentSource source, UUID createdBy, String manualNote) {

        /** Imported facts are the default; existing call sites stay source-aware only where it matters. */
        public Payment(UUID id, UUID companyId, UUID accountId, UUID importId, String externalOperationId,
                       Integer sourceRow, LocalDate operationDate, BigDecimal amount, Direction direction,
                       String counterpartyKey, String counterpartyInn, String counterpartyName, String purpose,
                       String categoryCode, CategorySource categorySource, String ruleVersion,
                       BigDecimal balanceEod, long version, Instant knownAt, Instant createdAt) {
            this(id, companyId, accountId, importId, externalOperationId, sourceRow, operationDate, amount, direction,
                    counterpartyKey, counterpartyInn, counterpartyName, purpose, categoryCode, categorySource,
                    ruleVersion, balanceEod, version, knownAt, createdAt, PaymentSource.IMPORT, null, null);
        }
    }

    /** Owner-entered fact. The account comes separately from the trusted path/body, never from a generated payment. */
    public record ManualPaymentDraft(UUID accountId, LocalDate operationDate, BigDecimal amount, Direction direction,
                                     String counterpartyInn, String counterpartyName, String purpose,
                                     String categoryCode, String note) {
    }

    /** One row of an atomic bulk category command. */
    public record CategoryChange(UUID paymentId, long expectedVersion) {
    }

    /** A payment with the sum of its active allocations, as exposed by the public contract. */
    public record PaymentView(Payment payment, BigDecimal allocatedAmount, Instant voidedAt, String voidReason,
                              UUID replacementPaymentId, DayStatus dayStatus) {
    }

    /** Category state of a payment at a knowledge cutoff (initial revision or a later correction). */
    public record CategoryRevision(UUID paymentId, String categoryCode, CategorySource categorySource,
                                   String ruleVersion, Instant knownAt, UUID actorId, long version) {
    }

    public record CompletenessRange(UUID id, UUID accountId, LocalDate fromDate, LocalDate toDate,
                                    Completeness completeness, String reason, Instant knownAt, UUID actorId) {
    }

    public record BalanceAnchor(UUID id, UUID accountId, LocalDate date, BigDecimal balance, AnchorSource source,
                                String reason, Instant knownAt, UUID actorId, UUID supersedesId) {
    }

    /** Flows/EOD are null for MISSING days; EOD may also be null on a complete day without an anchor. */
    public record DailyObservation(LocalDate date, BigDecimal inflow, BigDecimal outflow, BigDecimal balanceEod,
                                   DayStatus status, int paymentCount, boolean balanceMismatch) {
    }

    /** Facts needed to describe history quality without inventing missing days or balances. */
    public record QualityFacts(LocalDate fromDate, LocalDate toDate, int emptyPurposeCount) {
    }

    /** One actionable warning of the DataQualityRs contract; date is null for a warning about the whole period. */
    public record QualityWarning(String code, String message, LocalDate date) {
    }

    /** History quality over the observed payment period, derived from payments and the daily projection. */
    public record DataQuality(UUID accountId, LocalDate fromDate, LocalDate toDate, DataQualityLevel quality,
                              int completeDayCount, int missingDayCount, int balanceMismatchCount,
                              int emptyPurposeCount, List<QualityWarning> warnings) {
    }

    /** B(T) and the date of the EOD/anchor it was derived from (handoff §4.1, startBalanceDate). */
    public record StartBalance(BigDecimal balance, LocalDate anchorDate) {
    }
}
