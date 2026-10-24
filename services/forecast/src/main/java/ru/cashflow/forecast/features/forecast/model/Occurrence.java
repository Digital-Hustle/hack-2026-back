package ru.cashflow.forecast.features.forecast.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import ru.cashflow.forecast.features.shared.model.Direction;

public record Occurrence(UUID patternId, LocalDate nominalDate, LocalDate date, Direction direction,
        BigDecimal amount, String categoryCode, String counterpartyKey) { }
