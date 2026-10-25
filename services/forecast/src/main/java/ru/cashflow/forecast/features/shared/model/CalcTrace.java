package ru.cashflow.forecast.features.shared.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Request-local step journal. A disabled trace ignores all calls, so the numeric path is identical either way. */
public final class CalcTrace {
    private static final CalcTrace OFF = new CalcTrace(false);
    private final boolean enabled;
    private final List<TraceStep> steps = new ArrayList<>();

    private CalcTrace(boolean enabled) {
        this.enabled = enabled;
    }

    public static CalcTrace off() {
        return OFF;
    }

    public static CalcTrace on() {
        return new CalcTrace(true);
    }

    public boolean enabled() {
        return enabled;
    }

    public void step(String stage, String message) {
        step(stage, message, Map.of());
    }

    public void step(String stage, String message, Map<String, String> data) {
        if (enabled) {
            steps.add(new TraceStep(steps.size() + 1, stage, message, data));
        }
    }

    public static Map<String, String> data(String... keyValues) {
        var result = new LinkedHashMap<String, String>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            result.put(keyValues[index], keyValues[index + 1]);
        }
        return result;
    }

    public List<TraceStep> steps() {
        return List.copyOf(steps);
    }
}
