package ru.cashflow.forecast.features.backtest.model;

import java.math.BigDecimal;
import java.util.List;
import ru.cashflow.forecast.features.forecast.model.Flows;

/** A reference model's 30 daily balances and flows for one origin, built only from data known at the cutoff. */
public record Benchmark(String model, String description, boolean selectable, List<BigDecimal> balances,
        List<Flows> flows) {
    public Benchmark {
        balances = List.copyOf(balances);
        flows = List.copyOf(flows);
    }
}
