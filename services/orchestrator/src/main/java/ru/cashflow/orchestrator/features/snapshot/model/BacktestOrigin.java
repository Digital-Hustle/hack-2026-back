package ru.cashflow.orchestrator.features.snapshot.model;

import java.time.LocalDate;

/** Input preparation outcome, not a claim that Forecast has evaluated the origin. */
public record BacktestOrigin(LocalDate asOfDate, Status status, String reason) {

    public enum Status { INCLUDED, SKIPPED }
}
