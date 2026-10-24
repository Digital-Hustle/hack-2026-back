package ru.cashflow.orchestrator.api.controller;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.CompanyDtos.ItemsRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.BulkCategoryRq;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.CategoryListRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.CategoryRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.CounterpartyRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.PaymentRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.CategoryRq;
import ru.cashflow.orchestrator.api.dto.PageDtos.PageRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.ledger.application.LedgerService;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategoryChange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.PaymentFilter;
import ru.cashflow.orchestrator.features.paging.model.PageQuery;

/** Contract-shaped company-wide payment endpoints; the legacy account-scoped routes stay as aliases. */
@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}")
public class PaymentController {

    private final LedgerService service;

    public PaymentController(LedgerService service) {
        this.service = service;
    }

    @GetMapping("/payments")
    public PageRs<PaymentRs> payments(CurrentUser user, @PathVariable UUID companyId,
                                      @RequestParam UUID accountId,
                                      @RequestParam(required = false) LocalDate fromDate,
                                      @RequestParam(required = false) LocalDate toDate,
                                      @RequestParam(required = false) Direction direction,
                                      @RequestParam(required = false) String categoryCode,
                                      @RequestParam(required = false) String counterpartyKey,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "operationDate,desc") String sort,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        PaymentFilter filter = LedgerController.filter(fromDate, toDate, direction, categoryCode, counterpartyKey, q,
                sort);
        return PageRs.of(service.payments(user.userId(), companyId, accountId, filter, query), query,
                PaymentRs::of);
    }

    @GetMapping("/payments/{paymentId}")
    public PaymentRs payment(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID paymentId) {
        return PaymentRs.of(service.payment(user.userId(), companyId, paymentId));
    }

    @PatchMapping("/payments/{paymentId}/category")
    public PaymentRs changeCategory(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID paymentId,
                                    @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody CategoryRq rq) {
        return PaymentRs.of(service.changeCategoryByCompany(user.userId(), companyId, paymentId,
                CompanyController.version(ifMatch), rq.categoryCode()));
    }

    @PostMapping("/payments/category-changes")
    public ItemsRs<PaymentRs> changeCategories(CurrentUser user, @PathVariable UUID companyId,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @Valid @RequestBody BulkCategoryRq rq) {
        return new ItemsRs<>(service.changeCategories(user.userId(), companyId, rq.categoryCode(),
                rq.payments().stream().map(row -> new CategoryChange(row.paymentId(), row.expectedVersion())).toList(),
                idempotencyKey).stream().map(PaymentRs::of).toList());
    }

    @GetMapping("/categories")
    public CategoryListRs categories(CurrentUser user, @PathVariable UUID companyId) {
        return new CategoryListRs(service.categories(user.userId(), companyId).stream().map(CategoryRs::of).toList());
    }

    @GetMapping("/counterparties")
    public PageRs<CounterpartyRs> counterparties(CurrentUser user, @PathVariable UUID companyId,
                                                 @RequestParam UUID accountId,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        return PageRs.of(service.counterpartyPage(user.userId(), companyId, accountId, q, query), query,
                CounterpartyRs::of);
    }
}