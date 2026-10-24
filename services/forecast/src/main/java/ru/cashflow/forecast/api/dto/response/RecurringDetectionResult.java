package ru.cashflow.forecast.api.dto.response;

import java.util.List;

public record RecurringDetectionResult(String detectorVersion, List<RecurringPatternRs> patterns) { }
