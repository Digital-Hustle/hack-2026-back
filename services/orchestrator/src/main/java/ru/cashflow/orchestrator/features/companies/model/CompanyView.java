package ru.cashflow.orchestrator.features.companies.model;

/** Company with the role of the requesting user, so the response can expose permissions without extra reads. */
public record CompanyView(Company company, Role myRole) {
}