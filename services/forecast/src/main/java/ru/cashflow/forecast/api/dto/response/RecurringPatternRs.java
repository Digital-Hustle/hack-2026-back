package ru.cashflow.forecast.api.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import ru.cashflow.forecast.features.shared.model.Direction;
import ru.cashflow.forecast.features.shared.model.PatternStatus;

public record RecurringPatternRs(UUID id, UUID accountId, Direction direction, String categoryCode,
        String counterpartyKey, PatternStatus status, ScheduleRs schedule, BigDecimal amount, int observations,
        LocalDate firstSeen, LocalDate lastSeen, long inputDataVersion, String detectorVersion, long version) { }
