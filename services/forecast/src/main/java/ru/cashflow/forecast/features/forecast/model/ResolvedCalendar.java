package ru.cashflow.forecast.features.forecast.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.cashflow.forecast.features.shared.model.Warning;

public record ResolvedCalendar(Map<LocalDate, Flows> planned, Map<LocalDate, Flows> recurring,
        Set<StreamKey> baselineReplacements, List<Warning> warnings) {
    public ResolvedCalendar {
        planned = Map.copyOf(planned);
        recurring = Map.copyOf(recurring);
        baselineReplacements = Set.copyOf(baselineReplacements);
        warnings = List.copyOf(warnings);
    }
}
