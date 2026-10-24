package ru.cashflow.forecast.features.backtest.model;

public record CalibrationBin(double fromProbability, double toProbability, int count,
        Double meanProbability, Double observedRate) { }
