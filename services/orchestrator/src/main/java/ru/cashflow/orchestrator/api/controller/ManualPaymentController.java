package ru.cashflow.orchestrator.api.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.CompanyDtos.ItemsRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.ManualPaymentRq;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.PaymentRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.ledger.application.ManualPaymentCorrectionService.Decision;
import ru.cashflow.orchestrator.features.ledger.application.ManualPaymentCorrectionService;
import ru.cashflow.orchestrator.features.ledger.application.ManualPaymentService;

/** Owner/accountant-entered fact; accountId is part of the body, matching the public company-scoped contract. */
@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}")
public class ManualPaymentController {

    private final ManualPaymentService service;
    private final ManualPaymentCorrectionService corrections;

    public ManualPaymentController(ManualPaymentService service,
            ManualPaymentCorrectionService corrections) {
        this.corrections = corrections;
        this.service = service;
    }

    @PostMapping("/payments")
    public ResponseEntity<PaymentRs> create(CurrentUser user, @PathVariable UUID companyId,
                                            @RequestHeader("Idempotency-Key") String idempotencyKey,
                                            @Valid @RequestBody ManualPaymentRq rq) {
        var payment = service.create(user.userId(), companyId, rq.toDraft(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentRs.of(payment));
    }

    public record VoidRq(@NotBlank @Size(max = 500) String reason) {
    }

    public record ResolveRq(@NotNull UUID importedPaymentId,
                            @Min(1) long importedPaymentVersion,
                            @NotNull
                            Decision decision,
                            @NotBlank @Size(max = 500) String reason) {
    }

    @PostMapping("/payments/{paymentId}/void")
    public PaymentRs voidPayment(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID paymentId,
                                 @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody VoidRq rq) {
        return PaymentRs.of(corrections.voidPayment(user.userId(), companyId, paymentId,
                CompanyController.version(ifMatch), rq.reason()));
    }

    @GetMapping("/payments/{paymentId}/duplicate-candidates")
    public ItemsRs<PaymentRs> candidates(
            CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID paymentId) {
        return new ItemsRs<>(corrections
                .candidates(user.userId(), companyId, paymentId).stream().map(PaymentRs::of).toList());
    }

    @PostMapping("/payments/{paymentId}/duplicate-resolutions")
    public PaymentRs resolve(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID paymentId,
                             @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody ResolveRq rq) {
        return PaymentRs.of(corrections.resolve(user.userId(), companyId, paymentId,
                CompanyController.version(ifMatch), rq.importedPaymentId(), rq.importedPaymentVersion(),
                rq.decision(), rq.reason()));
    }
}
