package ru.cashflow.forecast.features.backtest.model;

import java.math.BigDecimal;
import java.util.List;

public record ScoringResult(int evaluatedDays, BigDecimal maeBalance, BigDecimal rmseBalance,
        List<HorizonMetric> horizons, GapMetrics gapMetrics, RiskMetrics riskMetrics) { }
