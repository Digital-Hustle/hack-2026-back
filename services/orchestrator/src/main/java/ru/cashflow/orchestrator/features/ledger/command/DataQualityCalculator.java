package ru.cashflow.orchestrator.features.ledger.command;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.DailyObservation;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.DayStatus;

/** Account history quality: an unknown day is never a confirmed zero-flow day. */
public final class DataQualityCalculator {

    private static final int WEEKDAYS = 7;

    private DataQualityCalculator() {
    }

    /** Shared with the dashboard, which reads a different (forecast settings) history window. */
    public static String quality(List<DailyObservation> days) {
        Set<DayOfWeek> weekdays = EnumSet.noneOf(DayOfWeek.class);
        boolean missing = false;
        for (DailyObservation day : days) {
            if (day.status() == DayStatus.MISSING) {
                missing = true;
            } else {
                weekdays.add(day.date().getDayOfWeek());
            }
        }
        return quality(weekdays.size(), missing);
    }

    /** Includes absent projection rows in missingDayCount; the inclusive window is caller-validated. */
    public static Summary summarize(LocalDate from, LocalDate to, List<DailyObservation> rows,
                                    long emptyPurposeCount) {
        Map<LocalDate, DailyObservation> byDate = new HashMap<>();
        rows.forEach(day -> byDate.put(day.date(), day));
        Set<DayOfWeek> weekdays = EnumSet.noneOf(DayOfWeek.class);
        int complete = 0;
        int missing = 0;
        int mismatch = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            DailyObservation observation = byDate.get(day);
            if (observation == null || observation.status() == DayStatus.MISSING) {
                missing++;
            } else {
                complete++;
                weekdays.add(day.getDayOfWeek());
                if (observation.balanceMismatch()) {
                    mismatch++;
                }
            }
        }
        List<Warning> warnings = new ArrayList<>();
        if (missing > 0) {
            warnings.add(new Warning("MISSING_DAYS", "Есть дни без подтверждённых данных: " + missing, null));
        }
        if (mismatch > 0) {
            warnings.add(new Warning("BALANCE_MISMATCH", "Найдены расхождения остатков: " + mismatch, null));
        }
        if (emptyPurposeCount > 0) {
            warnings.add(new Warning("EMPTY_PURPOSE", "Операции без назначения: " + emptyPurposeCount, null));
        }
        return new Summary(from, to, quality(weekdays.size(), missing > 0), complete, missing,
                mismatch, emptyPurposeCount, List.copyOf(warnings));
    }

    private static String quality(int knownWeekdays, boolean missing) {
        if (knownWeekdays < WEEKDAYS) {
            return "INSUFFICIENT";
        }
        return missing ? "LIMITED" : "GOOD";
    }

    public record Warning(String code, String message, LocalDate date) {
    }

    public record Summary(LocalDate fromDate, LocalDate toDate, String quality, int completeDayCount,
                          int missingDayCount, int balanceMismatchCount, long emptyPurposeCount,
                          List<Warning> warnings) {
    }
}
