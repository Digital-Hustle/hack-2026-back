package ru.cashflow.forecast.features.shared.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record Payment(UUID paymentId, LocalDate date, BigDecimal amount, Direction direction,
        String categoryCode, String counterpartyKey) { }
