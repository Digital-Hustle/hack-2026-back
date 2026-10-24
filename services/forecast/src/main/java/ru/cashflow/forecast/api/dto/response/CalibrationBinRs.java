package ru.cashflow.forecast.api.dto.response;

public record CalibrationBinRs(double fromProbability, double toProbability, int count,
        Double meanProbability, Double observedRate) { }
