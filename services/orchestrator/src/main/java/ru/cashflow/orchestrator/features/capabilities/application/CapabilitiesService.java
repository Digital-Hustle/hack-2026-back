package ru.cashflow.orchestrator.features.capabilities.application;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import ru.cashflow.orchestrator.features.capabilities.model.Capabilities;

/**
 * Single source of the public limits. Values are configuration, not magic numbers; defaults match the public upload
 * and paging contract (20 MiB, 50 000 rows, 200 items per page).
 */
@Service
public class CapabilitiesService {

    private static final String CURRENCY = "RUB";
    private static final List<Integer> FORECAST_HORIZONS = List.of(30);
    private static final List<String> RISK_METHODS = List.of("HISTORICAL_TRAJECTORY_BOOTSTRAP");

    private final String apiVersion;
    private final long maxCsvBytes;
    private final int maxImportRows;
    private final int maxPageSize;

    public CapabilitiesService(@Value("${orchestrator.capabilities.api-version:1.0}") String apiVersion,
                               @Value("${orchestrator.capabilities.max-csv-bytes:20971520}") long maxCsvBytes,
                               @Value("${orchestrator.capabilities.max-import-rows:50000}") int maxImportRows,
                               @Value("${orchestrator.capabilities.max-page-size:200}") int maxPageSize) {
        this.apiVersion = apiVersion;
        this.maxCsvBytes = maxCsvBytes;
        this.maxImportRows = maxImportRows;
        this.maxPageSize = maxPageSize;
    }

    public Capabilities get() {
        return new Capabilities(apiVersion, CURRENCY, FORECAST_HORIZONS, RISK_METHODS, maxCsvBytes, maxImportRows,
                maxPageSize);
    }
}
