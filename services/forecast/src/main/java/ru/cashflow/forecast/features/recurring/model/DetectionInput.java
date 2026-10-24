package ru.cashflow.forecast.features.recurring.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ru.cashflow.forecast.features.shared.model.Payment;

public record DetectionInput(UUID accountId, LocalDate asOfDate, long inputDataVersion, List<Payment> payments) {
    public DetectionInput { payments = List.copyOf(payments); }
}
