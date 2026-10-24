package ru.cashflow.orchestrator.api.controller;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.CompanyDtos.ItemsRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.AnchorRq;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.AnchorRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.CategoryRq;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.CompletenessRq;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.CompletenessRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.DailyActualRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.PaymentRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.RevisionRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.ledger.application.LedgerService;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}")
public class LedgerController {

    private final LedgerService service;

    public LedgerController(LedgerService service) {
        this.service = service;
    }

    @GetMapping("/payments")
    public ItemsRs<PaymentRs> payments(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                       @RequestParam LocalDate from, @RequestParam LocalDate to,
                                       @RequestParam(defaultValue = "100") int limit,
                                       @RequestParam(defaultValue = "0") int offset) {
        return new ItemsRs<>(service.payments(user.userId(), companyId, accountId, from, to, limit, offset).stream()
                .map(PaymentRs::of).toList());
    }

    @GetMapping("/payments/{paymentId}/revisions")
    public ItemsRs<RevisionRs> revisions(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                         @PathVariable UUID paymentId) {
        return new ItemsRs<>(service.revisions(user.userId(), companyId, accountId, paymentId).stream()
                .map(RevisionRs::of).toList());
    }

    @PutMapping("/payments/{paymentId}/category")
    public PaymentRs changeCategory(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                    @PathVariable UUID paymentId, @RequestHeader("If-Match") String ifMatch,
                                    @Valid @RequestBody CategoryRq rq) {
        return PaymentRs.of(service.changeCategory(user.userId(), companyId, accountId, paymentId,
                CompanyController.version(ifMatch), rq.categoryCode()));
    }

    @PostMapping("/completeness")
    public ResponseEntity<CompletenessRs> completeness(CurrentUser user, @PathVariable UUID companyId,
                                                       @PathVariable UUID accountId,
                                                       @Valid @RequestBody CompletenessRq rq) {
        var range = service.addCompleteness(user.userId(), companyId, accountId, rq.fromDate(), rq.toDate(),
                rq.completeness(), rq.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(CompletenessRs.of(range));
    }

    @PostMapping("/balance-anchors")
    public ResponseEntity<AnchorRs> anchor(CurrentUser user, @PathVariable UUID companyId,
                                           @PathVariable UUID accountId, @Valid @RequestBody AnchorRq rq) {
        var anchor = service.addManualAnchor(user.userId(), companyId, accountId, rq.date(), rq.balance(),
                rq.reason(), rq.supersedesId());
        return ResponseEntity.status(HttpStatus.CREATED).body(AnchorRs.of(anchor));
    }

    @GetMapping("/daily-history")
    public ItemsRs<DailyActualRs> history(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                          @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return new ItemsRs<>(service.observations(user.userId(), companyId, accountId, from, to).stream()
                .map(DailyActualRs::of).toList());
    }
}
