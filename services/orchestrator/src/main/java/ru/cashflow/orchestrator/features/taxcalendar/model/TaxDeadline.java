package ru.cashflow.orchestrator.features.taxcalendar.model;

import java.time.LocalDate;

/** Справочный срок уплаты: без суммы и без утверждения, что налог применим к компании. */
public record TaxDeadline(LocalDate dueDate, String title, String taxCode, String audience, String sourceUrl) {
}
