package ru.cashflow.orchestrator.features.forecast.gateway;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import ru.cashflow.orchestrator.features.snapshot.wire.ForecastWire;

/** Bounded, value-free diagnostics from an untrusted downstream problem response. */
public record ForecastDiagnostics(int httpStatus, String traceId, List<FieldError> errors) {
    private static final int MAX_ERRORS = 20;
    private static final Set<String> CONTRACT_FIELDS = Stream.concat(Stream.of("body", "field"),
            Arrays.stream(ForecastWire.class.getDeclaredClasses()).filter(Class::isRecord)
                    .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                    .map(java.lang.reflect.RecordComponent::getName))
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> ERROR_CODES = Set.of("INVALID_VALUE", "VALIDATION_FAILED", "MALFORMED_REQUEST",
            "INSUFFICIENT_HISTORY", "BALANCE_ANCHOR_REQUIRED", "UNRESOLVED_PLAN", "INPUT_INCOMPLETE", "CONFLICT");

    public ForecastDiagnostics {
        errors = List.copyOf(errors);
    }

    public static ForecastDiagnostics from(int httpStatus, JsonNode problem) {
        var errors = new java.util.ArrayList<FieldError>();
        if (problem != null && problem.path("errors").isArray()) {
            var source = problem.path("errors");
            for (int index = 0; index < Math.min(source.size(), MAX_ERRORS); index++) {
                var error = source.get(index);
                String field = safeField(error.path("field").asText(null));
                if (field != null) {
                    String code = error.path("code").asText("");
                    errors.add(new FieldError(field, ERROR_CODES.contains(code) ? code : "INVALID_VALUE",
                            "Forecast отклонил поле запроса"));
                }
            }
        }
        return new ForecastDiagnostics(httpStatus, trace(problem), errors.stream().distinct().toList());
    }

    public List<String> fields() {
        return errors.stream().map(FieldError::field).distinct().toList();
    }

    private static String safeField(String field) {
        if (field == null || field.length() > 256) {
            return null;
        }
        String normalized = field.replace(".[", "[");
        if (!normalized.matches("[A-Za-z][A-Za-z0-9_]*(?:\\[[0-9]{1,6}\\])?(?:\\.[A-Za-z][A-Za-z0-9_]*(?:\\[[0-9]{1,6}\\])?)*")) {
            return null;
        }
        String[] properties = normalized.replaceAll("\\[[0-9]+\\]", "").split("\\.");
        return properties.length <= 12 && Arrays.stream(properties).allMatch(CONTRACT_FIELDS::contains)
                ? normalized : null;
    }

    private static String trace(JsonNode problem) {
        if (problem == null || !problem.path("traceId").isTextual()) {
            return null;
        }
        String trace = problem.path("traceId").asText();
        if (!trace.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
            return null;
        }
        return UUID.fromString(trace).toString();
    }

    public record FieldError(String field, String code, String message) { }
}
