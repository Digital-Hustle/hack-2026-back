package ru.cashflow.orchestrator.features.plans.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;

/** Field names follow ExpectedPaymentRs of the Forecast contract (handoff §4). */
public final class PlanTypes {

    private PlanTypes() {
    }

    public enum PlanSource { MANUAL, INVOICE, TAX, CONTRACT }

    public enum Relation { ADDITIONAL, REPLACES_RECURRING, REPLACES_BASELINE }

    public enum PlanStatus { OPEN, PARTIALLY_SETTLED, SETTLED, CANCELLED }

    public record SourceDocument(String externalSystem, String externalId, String documentNumber,
                                 LocalDate issuedDate) {
    }

    public record ExpectedPayment(UUID id, UUID companyId, UUID accountId, String title, Direction direction,
                                  BigDecimal amount, LocalDate dueDate, String categoryCode, String counterpartyKey,
                                  String note, Relation relation, UUID recurringPatternId,
                                  String baselineCounterpartyKey, PlanSource source, SourceDocument sourceDocument,
                                  PlanStatus status, BigDecimal settledAmount, Instant knownAt, Instant createdAt,
                                  Instant updatedAt, long version) {

        public BigDecimal remainingAmount() {
            return status == PlanStatus.CANCELLED ? new BigDecimal("0.00") : amount.subtract(settledAmount);
        }
    }

    /** Editable plan content. Status and settledAmount are derived from cancellation and allocations only. */
    public record PlanDraft(String title, Direction direction, BigDecimal amount, LocalDate dueDate,
                            String categoryCode, String counterpartyKey, String note, Relation relation,
                            UUID recurringPatternId, String baselineCounterpartyKey, PlanSource source,
                            SourceDocument sourceDocument) {
    }

    public record Allocation(UUID id, UUID expectedPaymentId, UUID paymentId, BigDecimal amount, Instant createdAt,
                             UUID actorId, Instant reversedAt, UUID reversalActorId) {

        public boolean activeAt(Instant cutoff) {
            return createdAt.isBefore(cutoff) && (reversedAt == null || !reversedAt.isBefore(cutoff));
        }
    }
}
