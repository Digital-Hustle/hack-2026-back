package ru.cashflow.forecast.features.risk.model;

import java.util.List;
import ru.cashflow.forecast.features.forecast.model.Flows;

public record ErrorTrajectory(List<Flows> errors) {
    public ErrorTrajectory { errors = List.copyOf(errors); }
}
