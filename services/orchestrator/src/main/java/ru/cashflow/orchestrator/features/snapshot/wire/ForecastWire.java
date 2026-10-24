package ru.cashflow.orchestrator.features.snapshot.wire;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire DTOs of the Orchestrator → Forecast contract (ORCHESTRATOR_FORECAST_HANDOFF.md §4), written by hand.
 * Every field is always present: nullable fields serialize as explicit null and lists as [] (never omitted).
 * Money is a decimal string with exactly two digits; dates are YYYY-MM-DD; instants are UTC ISO-8601 with Z.
 * Field names are part of the contract and of the fingerprint; do not rename without a new algorithm version.
 */
public final class ForecastWire {

    private ForecastWire() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ForecastSettingsW(int windowWeeks, int horizonDays, String bufferAmount, int scenarioCount,
                                    int minCalibrationOrigins, int calibrationStepDays, String algorithm) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DailyActualW(String date, String inflow, String outflow, String balanceEod, String status,
                               int paymentCount, boolean balanceMismatch) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SnapshotPaymentW(String paymentId, String date, String amount, String direction,
                                   String categoryCode, String counterpartyKey) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CompletenessRangeW(String id, String accountId, String fromDate, String toDate, String completeness,
                                     String reason, String createdAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ScheduleW(String frequency, Integer weekday, Integer dayOfMonth, String businessDayRule) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RecurringPatternW(String id, String accountId, String direction, String categoryCode,
                                    String counterpartyKey, String status, ScheduleW schedule, String amount,
                                    int observations, String firstSeen, String lastSeen, long inputDataVersion,
                                    String detectorVersion, long version) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SourceDocumentW(String externalSystem, String externalId, String documentNumber, String issuedDate) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ExpectedPaymentW(String id, String companyId, String accountId, String title, String direction,
                                   String amount, String dueDate, String categoryCode, String counterpartyKey,
                                   String note, String relation, String recurringPatternId,
                                   String baselineCounterpartyKey, String source, SourceDocumentW sourceDocument,
                                   String status, String settledAmount, String remainingAmount, boolean overdue,
                                   String knownAt, String createdAt, String updatedAt, long version) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CalibrationOriginW(ForecastSnapshotW input, List<DailyActualW> actualDays,
                                     List<SnapshotPaymentW> actualPayments) {
    }

    /** Body of POST /internal/v1/forecast/calculate and element of BacktestSnapshot.originSnapshots. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ForecastSnapshotW(String snapshotId, String companyId, String accountId, String asOfDate,
                                    long inputDataVersion, String algorithmVersion, ForecastSettingsW settings,
                                    String scope, String startBalance, String startBalanceDate, String historyFrom,
                                    List<DailyActualW> dailyHistory, List<SnapshotPaymentW> payments,
                                    List<ExpectedPaymentW> knownPlans, List<RecurringPatternW> recurringPatterns,
                                    List<CompletenessRangeW> completeRanges,
                                    List<CalibrationOriginW> calibrationOrigins, String fingerprint) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BacktestRqW(String accountId, String firstOrigin, String lastOrigin, int stepDays, String scope,
                              int windowWeeks, boolean evaluateRisk) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BacktestSnapshotW(String snapshotId, String companyId, String accountId, long inputDataVersion,
                                    BacktestRqW request, ForecastSettingsW settings,
                                    List<ForecastSnapshotW> originSnapshots, List<DailyActualW> actualDays) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RecurringDetectionInputW(String accountId, String asOfDate, long inputDataVersion,
                                           List<SnapshotPaymentW> payments) {
    }
}
