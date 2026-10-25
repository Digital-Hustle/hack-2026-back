package ru.cashflow.forecast.features.shared.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecurringPattern(UUID id, UUID accountId, Direction direction, String categoryCode,
        String counterpartyKey, PatternStatus status, RecurringSchedule schedule, BigDecimal amount,
        int observations, LocalDate firstSeen, LocalDate lastSeen, long inputDataVersion,
        String detectorVersion, long version, boolean employeePayment) {
    public RecurringPattern(UUID id, UUID accountId, Direction direction, String categoryCode,
            String counterpartyKey, PatternStatus status, RecurringSchedule schedule, BigDecimal amount,
            int observations, LocalDate firstSeen, LocalDate lastSeen, long inputDataVersion,
            String detectorVersion, long version) {
        this(id, accountId, direction, categoryCode, counterpartyKey, status, schedule, amount,
                observations, firstSeen, lastSeen, inputDataVersion, detectorVersion, version, false);
    }
}
