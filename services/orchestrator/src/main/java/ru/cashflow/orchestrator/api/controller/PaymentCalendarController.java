package ru.cashflow.orchestrator.api.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.PaymentCalendarDtos.CalendarRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.paymentcalendar.application.PaymentCalendarService;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/payment-calendar")
public class PaymentCalendarController {

    private final PaymentCalendarService service;

    public PaymentCalendarController(PaymentCalendarService service) {
        this.service = service;
    }

    @GetMapping
    public CalendarRs get(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                           @RequestParam LocalDate fromDate, @RequestParam LocalDate toDate) {
        return CalendarRs.of(service.get(user.userId(), companyId, accountId, fromDate, toDate));
    }
}
