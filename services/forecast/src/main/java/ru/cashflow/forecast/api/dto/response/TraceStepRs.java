package ru.cashflow.forecast.api.dto.response;

import java.util.Map;

public record TraceStepRs(int seq, String stage, String message, Map<String, String> data) { }
