package ru.cashflow.orchestrator.features.alerts.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record GapDetails(UUID forecastId, UUID snapshotId, UUID gapId, LocalDate asOfDate, long inputDataVersion,
                         String staleness, Instant createdAt, JsonNode gap, String explanation,
                         List<PaymentFactor> mandatoryOutflows, List<PaymentFactor> expectedInflows,
                         List<JsonNode> overduePlans, List<ProjectedFlow> projectedFlows,
                         List<Limitation> dataLimitations, List<Recommendation> recommendations) {

    public record PaymentFactor(UUID id, String kind, String source, String title, String categoryCode,
                                LocalDate date, LocalDate nominalDate, String direction, String amount,
                                boolean inGapPeriod) {}

    public record ProjectedFlow(LocalDate date, String plannedIn, String plannedOut, String recurringIn,
                                String recurringOut, String residualIn, String residualOut) {}

    public record Limitation(String code, String message, LocalDate date) {}

    public record Recommendation(String code, String title, String message, List<UUID> relatedPlanIds) {}
}
