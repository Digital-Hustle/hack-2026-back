package ru.cashflow.orchestrator.api.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.AnalyticsDtos.CategoriesRs;
import ru.cashflow.orchestrator.api.dto.AnalyticsDtos.CounterpartiesRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.analytics.application.AnalyticsService;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/analytics")
public class AnalyticsController {

    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    public CategoriesRs categories(CurrentUser user, @PathVariable UUID companyId, @RequestParam UUID accountId,
                                   @RequestParam LocalDate fromDate, @RequestParam LocalDate toDate,
                                   @RequestParam(required = false) Direction direction) {
        return CategoriesRs.of(service.categories(user.userId(), companyId, accountId, fromDate, toDate, direction));
    }

    @GetMapping("/counterparties")
    public CounterpartiesRs counterparties(CurrentUser user, @PathVariable UUID companyId, @RequestParam UUID accountId,
                                           @RequestParam LocalDate fromDate, @RequestParam LocalDate toDate,
                                           @RequestParam(required = false) Direction direction) {
        return CounterpartiesRs.of(
                service.counterparties(user.userId(), companyId, accountId, fromDate, toDate, direction));
    }
}
