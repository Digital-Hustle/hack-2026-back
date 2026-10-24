package ru.cashflow.orchestrator.features.ledger.model;

/** Distinct counterparty of one account: the stable key plus the latest known display fields. */
public record Counterparty(String key, String inn, String name) {
}