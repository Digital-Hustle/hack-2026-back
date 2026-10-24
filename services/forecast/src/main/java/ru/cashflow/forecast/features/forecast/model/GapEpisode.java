package ru.cashflow.forecast.features.forecast.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import ru.cashflow.forecast.features.risk.model.RiskAvailability;

public record GapEpisode(UUID id, String kind, LocalDate fromDate, LocalDate toDate, BigDecimal threshold,
        BigDecimal expectedMinimumBalance, BigDecimal expectedShortfall, Double probability,
        BigDecimal shortfallP50, BigDecimal shortfallP90, String shortfallBasis,
        RiskAvailability riskEstimateAvailability) { }
