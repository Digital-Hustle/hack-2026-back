package ru.cashflow.orchestrator.api.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.PlanDtos.ExpectedPaymentImportRs;
import ru.cashflow.orchestrator.api.dto.PlanDtos.ImportExpectedPaymentsRq;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.companies.application.CompanyService;
import ru.cashflow.orchestrator.features.plans.application.PlanService;

/** Company-scoped atomic import of an external invoice/tax register (FR-13); it is not tied to one account. */
@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}")
public class ExpectedPaymentImportController {

    private final PlanService service;
    private final CompanyService companies;
    private final Clock clock;

    public ExpectedPaymentImportController(PlanService service, CompanyService companies, Clock clock) {
        this.service = service;
        this.companies = companies;
        this.clock = clock;
    }

    @PostMapping("/expected-payment-imports")
    public ResponseEntity<ExpectedPaymentImportRs> importExpectedPayments(
            CurrentUser user, @PathVariable UUID companyId, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ImportExpectedPaymentsRq rq) {
        var outcome = service.importExpectedPayments(user.userId(), companyId, rq.externalSource(),
                rq.toCommands(), idempotencyKey);
        String zone = companies.get(user.userId(), companyId).company().timezone();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(zone)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ExpectedPaymentImportRs.of(outcome, today));
    }
}
