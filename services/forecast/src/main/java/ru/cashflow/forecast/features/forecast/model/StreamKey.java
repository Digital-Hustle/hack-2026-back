package ru.cashflow.forecast.features.forecast.model;

import ru.cashflow.forecast.features.shared.model.Direction;

public record StreamKey(Direction direction, String counterpartyKey) { }
