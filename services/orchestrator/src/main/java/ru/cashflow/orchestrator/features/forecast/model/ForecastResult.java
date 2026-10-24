package ru.cashflow.orchestrator.features.forecast.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Validated ForecastRs (handoff §6) in domain types; produced only by ForecastResultValidator. */
public record ForecastResult(UUID id, UUID jobId, UUID snapshotId, UUID companyId, UUID accountId, LocalDate asOfDate,
                             int horizonDays, String scope, String algorithmVersion, long inputDataVersion,
                             BigDecimal startBalance, LocalDate startBalanceDate, String quality,
                             BigDecimal expectedMinimumBalance, LocalDate firstExpectedGapDate,
                             BigDecimal maxExpectedShortfall, List<Day> days, List<Gap> gaps, Risk risk) {

    public record Day(LocalDate date, BigDecimal plannedIn, BigDecimal plannedOut, BigDecimal recurringIn,
                      BigDecimal recurringOut, BigDecimal residualIn, BigDecimal residualOut, BigDecimal inflow,
                      BigDecimal outflow, BigDecimal balance, BigDecimal balanceP10, BigDecimal balanceP50,
                      BigDecimal balanceP90, BigDecimal pBelowZero, BigDecimal pBelowBuffer) {
    }

    public record Gap(UUID id, String kind, LocalDate fromDate, LocalDate toDate, BigDecimal threshold,
                      BigDecimal expectedMinimumBalance, BigDecimal expectedShortfall, BigDecimal probability,
                      BigDecimal shortfallP50, BigDecimal shortfallP90, String shortfallBasis,
                      String riskEstimateAvailability) {
    }

    public record Risk(String availability, String method, BigDecimal probability, int scenarioCount,
                       int calibrationOriginCount, boolean probabilityCalibrated) {
    }
}
