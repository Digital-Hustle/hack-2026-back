package ru.cashflow.orchestrator.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import ru.cashflow.orchestrator.api.dto.PlanDtos.SourceDocumentDto;
import ru.cashflow.orchestrator.features.paymentcalendar.model.PaymentCalendar;

public final class PaymentCalendarDtos {

    private PaymentCalendarDtos() {
    }

    public record CalendarRs(UUID companyId, UUID accountId, String currency, String timezone, LocalDate today,
                              long dataVersion, LocalDate fromDate, LocalDate toDate, TotalsRs totals, List<DayRs> days,
                              String overdueBeforeRangeAmount, List<ItemRs> overdueBeforeRange, List<String> warnings) {
        public static CalendarRs of(PaymentCalendar calendar) {
            return new CalendarRs(calendar.companyId(), calendar.accountId(), calendar.currency(), calendar.timezone(),
                    calendar.today(), calendar.dataVersion(), calendar.fromDate(), calendar.toDate(),
                    TotalsRs.of(calendar.totals()), calendar.days().stream().map(day -> new DayRs(day.date(),
                    TotalsRs.of(day.totals()), day.items().stream().map(ItemRs::of).toList())).toList(),
                    LedgerDtos.money(calendar.overdueBeforeRangeAmount()),
                    calendar.overdueBeforeRange().stream().map(ItemRs::of).toList(), calendar.warnings());
        }
    }

    public record TotalsRs(String amount, String settledAmount, String remainingAmount, String overdueAmount) {
        static TotalsRs of(PaymentCalendar.Totals totals) {
            return new TotalsRs(LedgerDtos.money(totals.amount()), LedgerDtos.money(totals.settledAmount()),
                    LedgerDtos.money(totals.remainingAmount()), LedgerDtos.money(totals.overdueAmount()));
        }
    }

    public record DayRs(LocalDate date, TotalsRs totals, List<ItemRs> items) {
    }

    public record ItemRs(String id, String kind, UUID expectedPaymentId, UUID recurringPatternId, String title,
                          LocalDate dueDate, LocalDate nominalDate, String categoryCode, String counterpartyKey,
                          String source, SourceDocumentDto sourceDocument, String status, String amount,
                          String settledAmount, String remainingAmount, boolean overdue, long version) {
        static ItemRs of(PaymentCalendar.Item item) {
            return new ItemRs(item.id(), item.kind().name(), item.expectedPaymentId(), item.recurringPatternId(),
                    item.title(), item.dueDate(), item.nominalDate(), item.categoryCode(), item.counterpartyKey(),
                    item.source(), SourceDocumentDto.of(item.sourceDocument()), item.status(), LedgerDtos.money(item.amount()),
                    item.settledAmount() == null ? null : LedgerDtos.money(item.settledAmount()),
                    LedgerDtos.money(item.remainingAmount()), item.overdue(), item.version());
        }
    }
}
