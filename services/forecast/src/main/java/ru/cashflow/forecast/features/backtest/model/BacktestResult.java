package ru.cashflow.forecast.features.backtest.model;

import java.math.BigDecimal;
import java.util.List;
import ru.cashflow.forecast.features.shared.model.Warning;

public record BacktestResult(String status, String algorithmVersion, int evaluatedOrigins, int skippedOrigins,
        int evaluatedDays, BigDecimal maeBalance, BigDecimal rmseBalance, List<HorizonMetric> horizons,
        GapMetrics gapMetrics, RiskMetrics riskMetrics, List<OriginResult> origins, List<Warning> warnings) {
    public BacktestResult {
        horizons = List.copyOf(horizons);
        origins = List.copyOf(origins);
        warnings = List.copyOf(warnings);
    }
}
