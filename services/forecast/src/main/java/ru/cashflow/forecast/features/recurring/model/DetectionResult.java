package ru.cashflow.forecast.features.recurring.model;

import java.util.List;
import ru.cashflow.forecast.features.shared.model.RecurringPattern;

public record DetectionResult(String detectorVersion, List<RecurringPattern> patterns) {
    public DetectionResult { patterns = List.copyOf(patterns); }
}
