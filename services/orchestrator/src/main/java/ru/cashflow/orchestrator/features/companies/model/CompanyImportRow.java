package ru.cashflow.orchestrator.features.companies.model;

import java.time.LocalDate;

/** Source metadata is informational, not evidence of an imported financial history. */
public record CompanyImportRow(String externalId, String name, String inn, LegalForm legalForm,
                               String industry, LocalDate firstOperationDate, LocalDate lastOperationDate,
                               int operationsCount) {
}
