package ru.cashflow.forecast.features.risk.model;

import java.util.List;
import ru.cashflow.forecast.features.forecast.model.ForecastInput;
import ru.cashflow.forecast.features.shared.model.DayFact;
import ru.cashflow.forecast.features.shared.model.Payment;

public record CalibrationOrigin(ForecastInput input, String fingerprint, List<DayFact> actualDays,
        List<Payment> actualPayments) {
    public CalibrationOrigin {
        actualDays = List.copyOf(actualDays);
        actualPayments = List.copyOf(actualPayments);
    }
}
