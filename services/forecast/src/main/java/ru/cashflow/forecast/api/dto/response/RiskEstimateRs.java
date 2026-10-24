package ru.cashflow.forecast.api.dto.response;

import java.util.List;
import ru.cashflow.forecast.features.risk.model.RiskAvailability;

public record RiskEstimateRs(RiskAvailability availability, String method, Double probability, int scenarioCount,
        int calibrationOriginCount, boolean probabilityCalibrated, List<String> assumptions) { }
