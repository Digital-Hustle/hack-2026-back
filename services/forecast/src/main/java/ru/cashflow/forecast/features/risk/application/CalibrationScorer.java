package ru.cashflow.forecast.features.risk.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import ru.cashflow.forecast.features.forecast.application.ResidualExtractor;
import ru.cashflow.forecast.features.forecast.application.SnapshotValidator;
import ru.cashflow.forecast.features.forecast.model.Flows;
import ru.cashflow.forecast.features.forecast.model.PointForecast;
import ru.cashflow.forecast.features.risk.model.CalibrationOrigin;
import ru.cashflow.forecast.features.risk.model.ErrorTrajectory;
import ru.cashflow.forecast.features.shared.model.InputException;

/** Outcomes are read only after fitting by the same point engine; never passed into its signature. */
@Component
public class CalibrationScorer {
    private final ResidualExtractor extractor;
    private final SnapshotValidator validator;

    public CalibrationScorer(ResidualExtractor extractor, SnapshotValidator validator) {
        this.extractor = extractor;
        this.validator = validator;
    }

    public ErrorTrajectory score(CalibrationOrigin origin, PointForecast fitted) {
        var input = origin.input();
        var dates = new HashSet<LocalDate>();
        boolean malformed = origin.actualDays().stream().anyMatch(day -> !dates.add(day.date())
                || !day.date().isAfter(input.asOfDate()) || day.date().isAfter(input.asOfDate().plusDays(30)));
        if (malformed) {
            throw new InputException("VALIDATION_FAILED", "calibrationOrigins.actualDays", "Duplicate or out-of-range scoring day");
        }
        validator.validateDays(origin.actualDays(), origin.actualPayments(), input.asOfDate().plusDays(1), input.asOfDate().plusDays(30), false);
        if (origin.actualDays().stream().anyMatch(day -> !day.complete())) {
            throw new InputException("INPUT_INCOMPLETE", "calibrationOrigins.actualDays", "Calibration outcome is incomplete");
        }
        var actualResiduals = extractor.extract(input, origin.actualDays(), origin.actualPayments(), fitted.baselineReplacements(), true);
        var errors = new ArrayList<Flows>();
        for (var day : fitted.days()) {
            var actual = actualResiduals.days().get(day.date());
            errors.add(new Flows(actual.inflow().subtract(day.residualIn()), actual.outflow().subtract(day.residualOut())));
        }
        return new ErrorTrajectory(errors);
    }
}
