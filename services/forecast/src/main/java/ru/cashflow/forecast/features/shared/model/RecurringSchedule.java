package ru.cashflow.forecast.features.shared.model;

public record RecurringSchedule(Frequency frequency, Integer weekday, Integer dayOfMonth,
        BusinessDayRule businessDayRule) { }
