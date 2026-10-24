package ru.cashflow.forecast.features.backtest.model;

import java.time.LocalDate;
import java.util.UUID;
import ru.cashflow.forecast.features.shared.model.ForecastScope;

public record BacktestRequest(UUID accountId, LocalDate firstOrigin, LocalDate lastOrigin, int stepDays,
        ForecastScope scope, int windowWeeks, boolean evaluateRisk) { }
