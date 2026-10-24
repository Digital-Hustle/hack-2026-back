package ru.cashflow.forecast.features.risk.model;

import java.util.List;

public record RiskEstimate(RiskAvailability availability, String method, Double probability,
        int scenarioCount, int calibrationOriginCount, boolean probabilityCalibrated, List<String> assumptions) {
    public RiskEstimate { assumptions = List.copyOf(assumptions); }
}
