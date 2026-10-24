package ru.cashflow.orchestrator.api.controller;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.CompanyDtos.ItemsRs;
import ru.cashflow.orchestrator.api.dto.PlanDtos.AllocationRq;
import ru.cashflow.orchestrator.api.dto.PlanDtos.AllocationRs;
import ru.cashflow.orchestrator.api.dto.PlanDtos.ExpectedPaymentRs;
import ru.cashflow.orchestrator.api.dto.PlanDtos.PlanRq;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.companies.application.CompanyService;
import ru.cashflow.orchestrator.features.plans.application.PlanService;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ExpectedPayment;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}")
public class PlanController {

    private final PlanService service;
    private final CompanyService companies;
    private final Clock clock;

    public PlanController(PlanService service, CompanyService companies, Clock clock) {
        this.service = service;
        this.companies = companies;
        this.clock = clock;
    }

    @PostMapping("/expected-payments")
    public ResponseEntity<ExpectedPaymentRs> create(CurrentUser user, @PathVariable UUID companyId,
                                                    @PathVariable UUID accountId, @Valid @RequestBody PlanRq rq) {
        var plan = service.create(user.userId(), companyId, accountId, rq.toDraft());
        return ResponseEntity.status(HttpStatus.CREATED).body(of(user, companyId, plan));
    }

    @GetMapping("/expected-payments")
    public ItemsRs<ExpectedPaymentRs> list(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                           @RequestParam LocalDate from, @RequestParam LocalDate to) {
        LocalDate today = today(user, companyId);
        return new ItemsRs<>(service.list(user.userId(), companyId, accountId, from, to).stream()
                .map(p -> ExpectedPaymentRs.of(p, today)).toList());
    }

    @GetMapping("/expected-payments/{planId}")
    public ExpectedPaymentRs get(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                 @PathVariable UUID planId) {
        return of(user, companyId, service.get(user.userId(), companyId, accountId, planId));
    }

    @PutMapping("/expected-payments/{planId}")
    public ExpectedPaymentRs update(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                    @PathVariable UUID planId, @RequestHeader("If-Match") String ifMatch,
                                    @Valid @RequestBody PlanRq rq) {
        return of(user, companyId, service.update(user.userId(), companyId, accountId, planId,
                CompanyController.version(ifMatch), rq.toDraft()));
    }

    @PostMapping("/expected-payments/{planId}/cancel")
    public ExpectedPaymentRs cancel(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                    @PathVariable UUID planId, @RequestHeader("If-Match") String ifMatch) {
        return of(user, companyId, service.cancel(user.userId(), companyId, accountId, planId,
                CompanyController.version(ifMatch)));
    }

    @PostMapping("/expected-payments/{planId}/allocations")
    public ResponseEntity<AllocationRs> allocate(CurrentUser user, @PathVariable UUID companyId,
                                                 @PathVariable UUID accountId, @PathVariable UUID planId,
                                                 @Valid @RequestBody AllocationRq rq) {
        var allocation = service.allocate(user.userId(), companyId, accountId, planId, rq.paymentId(), rq.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(AllocationRs.of(allocation));
    }

    @GetMapping("/expected-payments/{planId}/allocations")
    public ItemsRs<AllocationRs> allocations(CurrentUser user, @PathVariable UUID companyId,
                                             @PathVariable UUID accountId, @PathVariable UUID planId) {
        return new ItemsRs<>(service.allocations(user.userId(), companyId, accountId, planId).stream()
                .map(AllocationRs::of).toList());
    }

    @PostMapping("/allocations/{allocationId}/reverse")
    public ResponseEntity<Void> reverse(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                        @PathVariable UUID allocationId) {
        service.reverse(user.userId(), companyId, accountId, allocationId);
        return ResponseEntity.noContent().build();
    }

    /** Contract delete: same reversal semantics as POST .../reverse, scoped to the plan in the path. */
    @DeleteMapping("/expected-payments/{planId}/allocations/{allocationId}")
    public ResponseEntity<Void> deleteAllocation(CurrentUser user, @PathVariable UUID companyId,
                                                 @PathVariable UUID accountId, @PathVariable UUID planId,
                                                 @PathVariable UUID allocationId) {
        service.deleteAllocation(user.userId(), companyId, accountId, planId, allocationId);
        return ResponseEntity.noContent().build();
    }

    private ExpectedPaymentRs of(CurrentUser user, UUID companyId, ExpectedPayment plan) {
        return ExpectedPaymentRs.of(plan, today(user, companyId));
    }

    private LocalDate today(CurrentUser user, UUID companyId) {
        String zone = companies.get(user.userId(), companyId).company().timezone();
        return LocalDate.now(clock.withZone(java.time.ZoneId.of(zone)));
    }
}
