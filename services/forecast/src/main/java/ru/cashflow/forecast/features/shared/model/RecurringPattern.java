package ru.cashflow.forecast.features.shared.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecurringPattern(UUID id, UUID accountId, Direction direction, String categoryCode,
        String counterpartyKey, PatternStatus status, RecurringSchedule schedule, BigDecimal amount,
        int observations, LocalDate firstSeen, LocalDate lastSeen, long inputDataVersion,
        String detectorVersion, long version) { }
