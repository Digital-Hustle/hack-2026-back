package ru.cashflow.forecast.features.forecast.model;

import java.time.Instant;
import java.util.List;
import ru.cashflow.forecast.features.risk.model.RiskResult;
import ru.cashflow.forecast.features.shared.model.TraceStep;

public record CalculationResult(ForecastInput metadata, PointForecast point, RiskResult risk,
        List<GapEpisode> gaps, Instant createdAt, List<TraceStep> trace) {
    public CalculationResult {
        gaps = List.copyOf(gaps);
        trace = List.copyOf(trace);
    }

    public CalculationResult(ForecastInput metadata, PointForecast point, RiskResult risk,
            List<GapEpisode> gaps, Instant createdAt) {
        this(metadata, point, risk, gaps, createdAt, List.of());
    }
}
