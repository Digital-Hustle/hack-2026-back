package ru.cashflow.orchestrator.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategoryRevision;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.DailyObservation;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;

/** Money crosses the HTTP boundary as a decimal string with exactly two digits (handoff §4). */
public final class LedgerDtos {

    private LedgerDtos() {
    }

    public static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2).toPlainString();
    }

    public record PaymentRs(UUID id, UUID accountId, UUID importId, String externalOperationId, Integer sourceRow,
                            LocalDate operationDate, String amount, String direction, String counterpartyKey,
                            String counterpartyInn, String counterpartyName, String purpose, String categoryCode,
                            String categorySource, String ruleVersion, String balanceEod, long version,
                            Instant knownAt) {
        public static PaymentRs of(Payment p) {
            return new PaymentRs(p.id(), p.accountId(), p.importId(), p.externalOperationId(), p.sourceRow(),
                    p.operationDate(), money(p.amount()), p.direction().name(), p.counterpartyKey(),
                    p.counterpartyInn(), p.counterpartyName(), p.purpose(), p.categoryCode(),
                    p.categorySource().name(), p.ruleVersion(), money(p.balanceEod()), p.version(), p.knownAt());
        }
    }

    public record RevisionRs(String categoryCode, String categorySource, String ruleVersion, Instant knownAt,
                             UUID actorId, long version) {
        public static RevisionRs of(CategoryRevision r) {
            return new RevisionRs(r.categoryCode(), r.categorySource().name(), r.ruleVersion(), r.knownAt(),
                    r.actorId(), r.version());
        }
    }

    public record CategoryRq(@NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,63}$") String categoryCode) {
    }

    public record CompletenessRq(@NotNull LocalDate fromDate, @NotNull LocalDate toDate,
                                 @NotNull Completeness completeness, @NotBlank @Size(max = 500) String reason) {
    }

    public record CompletenessRs(UUID id, UUID accountId, LocalDate fromDate, LocalDate toDate, String completeness,
                                 String reason, Instant createdAt) {
        public static CompletenessRs of(CompletenessRange r) {
            return new CompletenessRs(r.id(), r.accountId(), r.fromDate(), r.toDate(), r.completeness().name(),
                    r.reason(), r.knownAt());
        }
    }

    public record AnchorRq(@NotNull LocalDate date, @NotNull @Digits(integer = 17, fraction = 2) BigDecimal balance,
                           @NotBlank @Size(max = 500) String reason, UUID supersedesId) {
    }

    public record AnchorRs(UUID id, UUID accountId, LocalDate date, String balance, String source, String reason,
                           Instant knownAt, UUID supersedesId) {
        public static AnchorRs of(BalanceAnchor a) {
            return new AnchorRs(a.id(), a.accountId(), a.date(), money(a.balance()), a.source().name(), a.reason(),
                    a.knownAt(), a.supersedesId());
        }
    }

    public record DailyActualRs(LocalDate date, String inflow, String outflow, String balanceEod, String status,
                                int paymentCount, boolean balanceMismatch) {
        public static DailyActualRs of(DailyObservation d) {
            return new DailyActualRs(d.date(), money(d.inflow()), money(d.outflow()), money(d.balanceEod()),
                    d.status().name(), d.paymentCount(), d.balanceMismatch());
        }
    }
}
