package ru.cashflow.orchestrator.api.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.PlanFactDtos.PlanFactRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.planfact.application.PlanFactService;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.GroupBy;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/plan-fact")
public class PlanFactController {

    private final PlanFactService service;

    public PlanFactController(PlanFactService service) {
        this.service = service;
    }

    @GetMapping
    public PlanFactRs get(CurrentUser user, @PathVariable UUID companyId, @RequestParam UUID accountId,
                          @RequestParam LocalDate fromDate, @RequestParam LocalDate toDate,
                          @RequestParam(defaultValue = "DAY") GroupBy groupBy) {
        return PlanFactRs.of(service.get(user.userId(), companyId, accountId, fromDate, toDate, groupBy));
    }
}
