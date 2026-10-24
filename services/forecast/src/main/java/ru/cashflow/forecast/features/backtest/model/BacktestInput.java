package ru.cashflow.forecast.features.backtest.model;

import java.util.List;
import java.util.UUID;
import ru.cashflow.forecast.features.forecast.model.CalculationInput;
import ru.cashflow.forecast.features.shared.model.DayFact;
import ru.cashflow.forecast.features.shared.model.Settings;

public record BacktestInput(UUID snapshotId, UUID companyId, UUID accountId, long inputDataVersion,
        BacktestRequest request, Settings settings, List<CalculationInput> originSnapshots, List<DayFact> actualDays) {
    public BacktestInput {
        originSnapshots = List.copyOf(originSnapshots);
        actualDays = List.copyOf(actualDays);
    }
}
