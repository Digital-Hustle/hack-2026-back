package ru.cashflow.orchestrator.api.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.CompanyDtos.ItemsRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.alerts.application.AlertService;
import ru.cashflow.orchestrator.features.alerts.application.GapDetailsService;
import ru.cashflow.orchestrator.features.alerts.model.AlertTypes.AlertView;
import ru.cashflow.orchestrator.features.alerts.model.GapDetails;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}")
public class AlertController {

    private final AlertService alerts;
    private final GapDetailsService details;

    public AlertController(AlertService alerts, GapDetailsService details) {
        this.alerts = alerts;
        this.details = details;
    }

    @GetMapping("/alerts")
    public ItemsRs<AlertView> list(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                   @RequestParam(defaultValue = "false") boolean activeOnly,
                                   @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return new ItemsRs<>(alerts.list(user.userId(), companyId, accountId, activeOnly, limit, offset));
    }

    @PostMapping("/alerts/{alertId}/acknowledge")
    public AlertView acknowledge(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                  @PathVariable UUID alertId) {
        return alerts.acknowledge(user.userId(), companyId, accountId, alertId);
    }

    @GetMapping("/forecasts/{forecastId}/gaps/{gapId}")
    public GapDetails details(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                              @PathVariable UUID forecastId, @PathVariable UUID gapId) {
        return details.get(user.userId(), companyId, accountId, forecastId, gapId);
    }
}
