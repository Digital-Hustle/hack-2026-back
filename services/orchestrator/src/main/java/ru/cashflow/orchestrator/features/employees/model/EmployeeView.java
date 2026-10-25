package ru.cashflow.orchestrator.features.employees.model;

import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.RecurringPattern;

public record EmployeeView(Employee employee, RecurringPattern recurringPayment) {
}
