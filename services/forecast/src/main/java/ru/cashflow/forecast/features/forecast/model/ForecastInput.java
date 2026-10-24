package ru.cashflow.forecast.features.forecast.model;

import ru.cashflow.forecast.features.shared.model.CompletenessRange;
import ru.cashflow.forecast.features.shared.model.DayFact;
import ru.cashflow.forecast.features.shared.model.ForecastScope;
import ru.cashflow.forecast.features.shared.model.KnownPlan;
import ru.cashflow.forecast.features.shared.model.Payment;
import ru.cashflow.forecast.features.shared.model.RecurringPattern;
import ru.cashflow.forecast.features.shared.model.Settings;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Point-engine input deliberately has no calibration outcomes or backtest scoring data. */
public record ForecastInput(
        UUID snapshotId,
        UUID companyId,
        UUID accountId,
        LocalDate asOfDate,
        Instant knowledgeCutoff,
        String timezone,
        long inputDataVersion,
        String algorithmVersion,
        Settings settings,
        ForecastScope scope,
        BigDecimal startBalance,
        LocalDate startBalanceDate,
        LocalDate historyFrom,
        List<DayFact> dailyHistory,
        List<Payment> payments,
        List<KnownPlan> knownPlans,
        List<RecurringPattern> recurringPatterns,
        List<CompletenessRange> completeRanges) {
    public ForecastInput {
        dailyHistory = List.copyOf(dailyHistory);
        payments = List.copyOf(payments);
        knownPlans = List.copyOf(knownPlans);
        recurringPatterns = List.copyOf(recurringPatterns);
        completeRanges = List.copyOf(completeRanges);
    }
}
