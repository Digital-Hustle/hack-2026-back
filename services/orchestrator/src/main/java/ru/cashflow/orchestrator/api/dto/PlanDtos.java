package ru.cashflow.orchestrator.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.Allocation;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ExpectedPayment;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ExternalPlan;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ExternalSource;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ImportOutcome;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanDraft;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanSource;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.Relation;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.SourceDocument;

public final class PlanDtos {

    private PlanDtos() {
    }

    public record SourceDocumentDto(@NotBlank @Size(max = 64) String externalSystem,
                                    @NotBlank @Size(max = 128) String externalId,
                                    @Size(max = 64) String documentNumber, LocalDate issuedDate) {
        SourceDocument toModel() {
            return new SourceDocument(externalSystem, externalId, documentNumber, issuedDate);
        }

        static SourceDocumentDto of(SourceDocument d) {
            return d == null ? null : new SourceDocumentDto(d.externalSystem(), d.externalId(), d.documentNumber(),
                    d.issuedDate());
        }
    }

    public record PlanRq(@NotBlank @Size(max = 200) String title, @NotNull Direction direction,
                         @NotNull @Digits(integer = 17, fraction = 2) BigDecimal amount, @NotNull LocalDate dueDate,
                         @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$") String categoryCode,
                         @Size(max = 600) String counterpartyKey, @Size(max = 1000) String note,
                         @NotNull Relation relation, UUID recurringPatternId,
                         @Size(max = 600) String baselineCounterpartyKey, @NotNull PlanSource source,
                         @Valid SourceDocumentDto sourceDocument) {
        public PlanDraft toDraft() {
            return new PlanDraft(title, direction, amount, dueDate, categoryCode, counterpartyKey, note, relation,
                    recurringPatternId, baselineCounterpartyKey, source,
                    sourceDocument == null ? null : sourceDocument.toModel());
        }
    }

    public record ExternalExpectedPaymentItem(@NotNull UUID accountId, @NotBlank @Size(max = 200) String title,
                                              @NotNull Direction direction,
                                              @NotNull @DecimalMin("0.01")
                                              @Digits(integer = 17, fraction = 2) BigDecimal amount,
                                              @NotNull LocalDate dueDate, @NotBlank @Size(max = 64) String categoryCode,
                                              @Size(max = 600) String counterpartyKey, @Size(max = 1000) String note,
                                              @NotNull Relation relation, UUID recurringPatternId,
                                              @Size(max = 600) String baselineCounterpartyKey,
                                              @NotNull PlanSource source,
                                              @NotNull @Valid SourceDocumentDto sourceDocument,
                                              @PositiveOrZero Long expectedVersion) {

        public ExternalPlan toCommand() {
            PlanDraft draft = new PlanDraft(title, direction, amount, dueDate, categoryCode, counterpartyKey, note,
                    relation, recurringPatternId, baselineCounterpartyKey, source, sourceDocument.toModel());
            return new ExternalPlan(accountId, draft, expectedVersion);
        }
    }

    public record ImportExpectedPaymentsRq(@NotNull ExternalSource externalSource,
                                           @NotNull @Size(min = 1, max = 500)
                                           List<@Valid ExternalExpectedPaymentItem> items) {

        public List<ExternalPlan> toCommands() {
            return items.stream().map(ExternalExpectedPaymentItem::toCommand).toList();
        }
    }

    public record ExpectedPaymentImportRs(int createdCount, int updatedCount, int unchangedCount,
                                          List<ExpectedPaymentRs> items) {

        public static ExpectedPaymentImportRs of(ImportOutcome outcome, LocalDate today) {
            return new ExpectedPaymentImportRs(outcome.createdCount(), outcome.updatedCount(),
                    outcome.unchangedCount(),
                    outcome.items().stream().map(plan -> ExpectedPaymentRs.of(plan, today)).toList());
        }
    }

    /** Same field set and order as ExpectedPaymentRs in the Forecast contract (handoff §4). */
    public record ExpectedPaymentRs(UUID id, UUID companyId, UUID accountId, String title, String direction,
                                    String amount, LocalDate dueDate, String categoryCode, String counterpartyKey,
                                    String note, String relation, UUID recurringPatternId,
                                    String baselineCounterpartyKey, String source, SourceDocumentDto sourceDocument,
                                    String status, String settledAmount, String remainingAmount, boolean overdue,
                                    Instant knownAt, Instant createdAt, Instant updatedAt, long version) {
        public static ExpectedPaymentRs of(ExpectedPayment p, LocalDate today) {
            boolean open = p.status() == ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanStatus.OPEN
                    || p.status() == ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanStatus.PARTIALLY_SETTLED;
            return new ExpectedPaymentRs(p.id(), p.companyId(), p.accountId(), p.title(), p.direction().name(),
                    LedgerDtos.money(p.amount()), p.dueDate(), p.categoryCode(), p.counterpartyKey(), p.note(),
                    p.relation().name(), p.recurringPatternId(), p.baselineCounterpartyKey(), p.source().name(),
                    SourceDocumentDto.of(p.sourceDocument()), p.status().name(), LedgerDtos.money(p.settledAmount()),
                    LedgerDtos.money(p.remainingAmount()), open && p.dueDate().isBefore(today), p.knownAt(),
                    p.createdAt(), p.updatedAt(), p.version());
        }
    }

    public record AllocationRq(@NotNull UUID paymentId,
                               @NotNull @Digits(integer = 17, fraction = 2) BigDecimal amount) {
    }

    public record AllocationRs(UUID id, UUID expectedPaymentId, UUID paymentId, String amount, Instant createdAt,
                               Instant reversedAt) {
        public static AllocationRs of(Allocation a) {
            return new AllocationRs(a.id(), a.expectedPaymentId(), a.paymentId(), LedgerDtos.money(a.amount()),
                    a.createdAt(), a.reversedAt());
        }
    }
}
