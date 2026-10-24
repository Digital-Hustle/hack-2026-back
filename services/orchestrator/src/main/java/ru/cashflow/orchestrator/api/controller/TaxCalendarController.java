package ru.cashflow.orchestrator.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.taxcalendar.application.TaxCalendarService;
import ru.cashflow.orchestrator.features.taxcalendar.model.TaxDeadline;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/tax-calendar")
public class TaxCalendarController {

    private final TaxCalendarService service;

    public TaxCalendarController(TaxCalendarService service) {
        this.service = service;
    }

    @GetMapping
    public TaxCalendarRs list(CurrentUser user, @PathVariable UUID companyId,
                              @RequestParam(defaultValue = "2026") int year) {
        return new TaxCalendarRs(year, service.list(user.userId(), companyId, year));
    }

    public record TaxCalendarRs(int year, List<TaxDeadline> items) {
    }
}
