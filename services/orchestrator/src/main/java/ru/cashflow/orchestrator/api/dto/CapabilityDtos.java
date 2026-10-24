package ru.cashflow.orchestrator.api.dto;

import java.util.List;

import ru.cashflow.orchestrator.features.capabilities.model.Capabilities;

/** CapabilitiesRs of the public contract (GET /api/v1/orchestrator/capabilities). */
public final class CapabilityDtos {

    private CapabilityDtos() {
    }

    public record CapabilitiesRs(String apiVersion, String currency, List<Integer> forecastHorizons,
                                 List<String> riskMethods, long maxCsvBytes, int maxImportRows, int maxPageSize) {

        public static CapabilitiesRs of(Capabilities capabilities) {
            return new CapabilitiesRs(capabilities.apiVersion(), capabilities.currency(),
                    capabilities.forecastHorizons(), capabilities.riskMethods(), capabilities.maxCsvBytes(),
                    capabilities.maxImportRows(), capabilities.maxPageSize());
        }
    }
}
