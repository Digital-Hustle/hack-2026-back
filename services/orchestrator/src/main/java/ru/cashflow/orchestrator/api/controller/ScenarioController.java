package ru.cashflow.orchestrator.api.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.ScenarioDtos.ChangeRq;
import ru.cashflow.orchestrator.api.dto.ScenarioDtos.ScenarioRq;
import ru.cashflow.orchestrator.api.dto.ScenarioDtos.ScenarioRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioService;

/** Negative "what if" scenarios: a stateless preview of how the base forecast changes. Any member may call it. */
@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/scenarios")
public class ScenarioController {

    private final ScenarioService service;

    public ScenarioController(ScenarioService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    public ScenarioRs preview(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                              @Valid @RequestBody ScenarioRq rq) {
        return ScenarioRs.of(service.preview(user.userId(), companyId, accountId, rq.forecastId(),
                rq.changes().stream().map(ChangeRq::toSpec).toList()));
    }
}
