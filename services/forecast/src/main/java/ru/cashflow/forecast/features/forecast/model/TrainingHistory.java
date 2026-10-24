package ru.cashflow.forecast.features.forecast.model;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import ru.cashflow.forecast.features.shared.model.DataQuality;
import ru.cashflow.forecast.features.shared.model.DayFact;
import ru.cashflow.forecast.features.shared.model.Payment;
import ru.cashflow.forecast.features.shared.model.Warning;

public record TrainingHistory(List<DayFact> days, List<Payment> payments, Map<DayOfWeek, Integer> sampleCounts,
        DataQuality quality, List<Warning> warnings) {
    public TrainingHistory {
        days = List.copyOf(days);
        payments = List.copyOf(payments);
        sampleCounts = Map.copyOf(sampleCounts);
        warnings = List.copyOf(warnings);
    }
}
