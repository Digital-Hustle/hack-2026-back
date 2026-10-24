package ru.cashflow.orchestrator.api.controller;

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

import ru.cashflow.orchestrator.api.dto.LedgerDtos.ManualPaymentRq;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.PaymentRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.ledger.application.ManualPaymentService;

/** Owner/accountant-entered fact; accountId is part of the body, matching the public company-scoped contract. */
@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}")
public class ManualPaymentController {

    private final ManualPaymentService service;

    public ManualPaymentController(ManualPaymentService service) {
        this.service = service;
    }

    @PostMapping("/payments")
    public ResponseEntity<PaymentRs> create(CurrentUser user, @PathVariable UUID companyId,
                                            @RequestHeader("Idempotency-Key") String idempotencyKey,
                                            @Valid @RequestBody ManualPaymentRq rq) {
        var payment = service.create(user.userId(), companyId, rq.toDraft(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentRs.of(payment));
    }
}
