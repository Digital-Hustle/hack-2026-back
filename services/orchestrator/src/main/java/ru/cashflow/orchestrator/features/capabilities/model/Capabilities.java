package ru.cashflow.orchestrator.features.capabilities.model;

import java.util.List;

/** Static capabilities and limits of the public API, safe to expose before a company is selected. */
public record Capabilities(String apiVersion, String currency, List<Integer> forecastHorizons,
                           List<String> riskMethods, long maxCsvBytes, int maxImportRows, int maxPageSize) {
}
