package ru.cashflow.forecast.features.forecast.model;

import java.time.Instant;
import java.util.List;
import ru.cashflow.forecast.features.risk.model.RiskResult;

public record CalculationResult(ForecastInput metadata, PointForecast point, RiskResult risk,
        List<GapEpisode> gaps, Instant createdAt) {
    public CalculationResult { gaps = List.copyOf(gaps); }
}
