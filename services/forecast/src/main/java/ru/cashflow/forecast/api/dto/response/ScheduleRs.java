package ru.cashflow.forecast.api.dto.response;

import ru.cashflow.forecast.features.shared.model.BusinessDayRule;
import ru.cashflow.forecast.features.shared.model.Frequency;

public record ScheduleRs(Frequency frequency, Integer weekday, Integer dayOfMonth, BusinessDayRule businessDayRule) { }
