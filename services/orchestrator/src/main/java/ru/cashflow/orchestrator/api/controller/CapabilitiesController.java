package ru.cashflow.orchestrator.api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.CapabilityDtos.CapabilitiesRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.capabilities.application.CapabilitiesService;

/** Bootstrap endpoint: it is not company-scoped, but it still requires the trusted Gateway identity. */
@RestController
@RequestMapping("/api/v1/orchestrator")
public class CapabilitiesController {

    private final CapabilitiesService service;

    public CapabilitiesController(CapabilitiesService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public CapabilitiesRs capabilities(CurrentUser user) {
        return CapabilitiesRs.of(service.get());
    }
}
