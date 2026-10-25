package ru.cashflow.forecast.features.shared.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One human-readable step of a calculation. Values are pre-formatted strings; no raw payment bodies are logged. */
public record TraceStep(int seq, String stage, String message, Map<String, String> data) {
    public TraceStep {
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}
