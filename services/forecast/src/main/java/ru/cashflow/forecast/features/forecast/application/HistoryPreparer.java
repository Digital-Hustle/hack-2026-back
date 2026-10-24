package ru.cashflow.forecast.features.forecast.application;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import org.springframework.stereotype.Component;
import ru.cashflow.forecast.features.forecast.model.ForecastInput;
import ru.cashflow.forecast.features.forecast.model.TrainingHistory;
import ru.cashflow.forecast.features.shared.model.DataQuality;
import ru.cashflow.forecast.features.shared.model.DayFact;
import ru.cashflow.forecast.features.shared.model.InputException;
import ru.cashflow.forecast.features.shared.model.Payment;
import ru.cashflow.forecast.features.shared.model.Warning;

@Component
public class HistoryPreparer {
    public TrainingHistory prepare(ForecastInput input) {
        int windowDays = input.settings().windowWeeks() * 7;
        var from = input.asOfDate().minusDays(windowDays - 1L);
        var days = input.dailyHistory().stream().filter(day -> !day.date().isBefore(from))
                .sorted(Comparator.comparing(DayFact::date)).toList();
        var payments = input.payments().stream().filter(payment -> !payment.date().isBefore(from))
                .sorted(Comparator.comparing(Payment::date).thenComparing(payment -> payment.paymentId().toString())).toList();
        var counts = new EnumMap<DayOfWeek, Integer>(DayOfWeek.class);
        for (var weekday : DayOfWeek.values()) {
            counts.put(weekday, 0);
        }
        var warnings = new ArrayList<Warning>();
        if (days.size() < windowDays) {
            warnings.add(new Warning("SHORT_HISTORY", "Available training days: " + days.size() + " of " + windowDays, null));
        }
        DayFact previous = null;
        for (var day : days) {
            if (day.complete()) {
                counts.merge(day.date().getDayOfWeek(), 1, Integer::sum);
            } else {
                warnings.add(new Warning("MISSING_DATA", "Unknown day excluded from weekday denominator", day.date()));
            }
            boolean mismatch = day.balanceMismatch();
            if (previous != null && previous.date().plusDays(1).equals(day.date()) && day.complete()
                    && previous.balanceEod() != null && day.balanceEod() != null) {
                mismatch |= previous.balanceEod().add(day.inflow()).subtract(day.outflow()).compareTo(day.balanceEod()) != 0;
            }
            if (mismatch) {
                warnings.add(new Warning("BALANCE_MISMATCH", "Observed balance disagrees with confirmed flows", day.date()));
            }
            previous = day;
        }
        if (counts.values().stream().anyMatch(count -> count == 0)) {
            throw new InputException("INSUFFICIENT_HISTORY", "dailyHistory", "At least one complete observation per weekday required");
        }
        DataQuality quality = warnings.isEmpty() ? DataQuality.GOOD : DataQuality.LIMITED;
        return new TrainingHistory(days, payments, counts, quality, warnings);
    }
}
