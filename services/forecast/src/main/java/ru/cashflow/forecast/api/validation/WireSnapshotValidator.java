package ru.cashflow.forecast.api.validation;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.cashflow.forecast.api.dto.request.ForecastSnapshot;
import ru.cashflow.forecast.api.json.SnapshotFingerprint;
import ru.cashflow.forecast.features.shared.model.InputException;

/** Wire-only checks, before removing scoring outcomes from the point input. */
@Component
public class WireSnapshotValidator {
    private final ObjectMapper json;

    public WireSnapshotValidator(ObjectMapper json) { this.json = json; }

    public void validate(ForecastSnapshot snapshot) {
        for (var origin : snapshot.calibrationOrigins()) {
            if (!origin.input().calibrationOrigins().isEmpty()) {
                throw new InputException("VALIDATION_FAILED", "calibrationOrigins.input.calibrationOrigins", "Recursive calibration input forbidden");
            }
            validateFingerprint(origin.input());
        }
        validateFingerprint(snapshot);
    }

    private void validateFingerprint(ForecastSnapshot snapshot) {
        if (!snapshot.fingerprint().equals(SnapshotFingerprint.compute(json.valueToTree(snapshot)))) {
            throw new InputException("VALIDATION_FAILED", "fingerprint", "Snapshot fingerprint mismatch");
        }
    }
}
