package ru.cashflow.orchestrator.features.alerts.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class AlertTypes {

    private AlertTypes() {}

    public enum Code { CASH_GAP, BUFFER_BREACH, OVERDUE_PLAN, DATA_QUALITY }

    public record Draft(String eventKey, UUID gapId, UUID planId, Code code, String title, String message,
                        LocalDate fromDate, LocalDate toDate, BigDecimal amount, BigDecimal probability) {}

    public record SavedAlert(UUID id, UUID companyId, UUID accountId, UUID forecastId, UUID gapId, UUID planId,
                             Code code, String title, String message, LocalDate fromDate, LocalDate toDate,
                             BigDecimal amount, BigDecimal probability, boolean active, Instant acknowledgedAt,
                             Instant createdAt, long inputDataVersion, boolean latestForecast) {}

    /** The amount is shortfall for a gap and unpaid remaining amount for an overdue plan. */
    public record AlertView(UUID id, UUID companyId, UUID accountId, UUID forecastId, UUID gapId, UUID planId,
                            Code code, String title, String message, LocalDate fromDate, LocalDate toDate,
                            String amount, BigDecimal probability, boolean active, Instant acknowledgedAt,
                            String staleness, long inputDataVersion, Instant createdAt) {}

    public record GapSource(UUID forecastId, UUID snapshotId, String response, String snapshot, Instant createdAt,
                            long inputDataVersion, boolean latestForecast) {}
}
