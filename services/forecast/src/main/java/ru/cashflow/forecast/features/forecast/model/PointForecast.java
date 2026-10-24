package ru.cashflow.forecast.features.forecast.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import ru.cashflow.forecast.features.shared.model.DataQuality;
import ru.cashflow.forecast.features.shared.model.Warning;

public record PointForecast(List<PointDay> days, DataQuality quality, List<Warning> warnings,
        Set<StreamKey> baselineReplacements, BigDecimal expectedMinimumBalance, LocalDate firstExpectedGapDate,
        BigDecimal maxExpectedShortfall) {
    public PointForecast {
        days = List.copyOf(days);
        warnings = List.copyOf(warnings);
        baselineReplacements = Set.copyOf(baselineReplacements);
    }
}
