package ru.cashflow.orchestrator.features.paymentcalendar.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import ru.cashflow.orchestrator.features.plans.model.PlanTypes.SourceDocument;

/** Current outgoing commitments; recurring occurrences are estimates, not settlement records. */
public record PaymentCalendar(UUID companyId, UUID accountId, String currency, String timezone, LocalDate today,
                              long dataVersion, LocalDate fromDate, LocalDate toDate, Totals totals, List<Day> days,
                              BigDecimal overdueBeforeRangeAmount, List<Item> overdueBeforeRange,
                              List<String> warnings) {

    public enum Kind { PLANNED, RECURRING }

    public record Item(String id, Kind kind, UUID expectedPaymentId, UUID recurringPatternId, String title,
                       LocalDate dueDate, LocalDate nominalDate, String categoryCode, String counterpartyKey,
                       String source, SourceDocument sourceDocument, String status, BigDecimal amount,
                       BigDecimal settledAmount, BigDecimal remainingAmount, boolean overdue, long version) {
    }

    public record Totals(BigDecimal amount, BigDecimal settledAmount, BigDecimal remainingAmount,
                         BigDecimal overdueAmount) {
    }

    public record Day(LocalDate date, Totals totals, List<Item> items) {
    }
}
