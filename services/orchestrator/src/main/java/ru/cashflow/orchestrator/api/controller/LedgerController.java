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
import ru.cashflow.orchestrator.api.dto.LedgerDtos.BulkCategoryRq;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.CategoryRq;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.CompletenessRq;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.CompletenessRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.DailyActualRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.DataQualityRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.PaymentRs;
import ru.cashflow.orchestrator.api.dto.LedgerDtos.RevisionRs;
import ru.cashflow.orchestrator.api.dto.PageDtos.PageRs;
import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.ledger.application.LedgerService;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategoryChange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.PaymentFilter;
import ru.cashflow.orchestrator.features.paging.model.PageQuery;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}")
public class LedgerController {

    private final LedgerService service;

    public LedgerController(LedgerService service) {
        this.service = service;
    }

    @GetMapping("/payments")
    public PageRs<PaymentRs> payments(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                      @RequestParam(required = false) LocalDate fromDate,
                                      @RequestParam(required = false) LocalDate toDate,
                                      @RequestParam(required = false) LocalDate from,
                                      @RequestParam(required = false) LocalDate to,
                                      @RequestParam(required = false) Direction direction,
                                      @RequestParam(required = false) String categoryCode,
                                      @RequestParam(required = false) String counterpartyKey,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "operationDate,desc") String sort,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        PaymentFilter filter = filter(fromDate == null ? from : fromDate, toDate == null ? to : toDate, direction,
                categoryCode, counterpartyKey, q, sort);
        return PageRs.of(service.payments(user.userId(), companyId, accountId, filter, query), query,
                PaymentRs::of);
    }

    @GetMapping("/payments/{paymentId}")
    public PaymentRs payment(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                             @PathVariable UUID paymentId) {
        return PaymentRs.of(service.payment(user.userId(), companyId, paymentId));
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

    @PostMapping("/payments/category-changes")
    public ItemsRs<PaymentRs> changeCategories(CurrentUser user, @PathVariable UUID companyId,
                                               @PathVariable UUID accountId,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @Valid @RequestBody BulkCategoryRq rq) {
        return new ItemsRs<>(service.changeCategories(user.userId(), companyId, rq.categoryCode(),
                rq.payments().stream().map(row -> new CategoryChange(row.paymentId(), row.expectedVersion())).toList(),
                idempotencyKey).stream().map(PaymentRs::of).toList());
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

    /** Public alias of /daily-history; accepts both the contract (fromDate/toDate) and legacy (from/to) names. */
    @GetMapping("/daily-actuals")
    public ItemsRs<DailyActualRs> actuals(CurrentUser user, @PathVariable UUID companyId,
                                          @PathVariable UUID accountId,
                                          @RequestParam(required = false) LocalDate fromDate,
                                          @RequestParam(required = false) LocalDate toDate,
                                          @RequestParam(required = false) LocalDate from,
                                          @RequestParam(required = false) LocalDate to) {
        LocalDate start = fromDate != null ? fromDate : from;
        LocalDate end = toDate != null ? toDate : to;
        if (start == null || end == null) {
            throw new ApiException(400, "BAD_REQUEST", "Параметры fromDate/toDate обязательны");
        }
        return new ItemsRs<>(service.observations(user.userId(), companyId, accountId, start, end).stream()
                .map(DailyActualRs::of).toList());
    }

    @GetMapping("/data-quality")
    public DataQualityRs dataQuality(CurrentUser user, @PathVariable UUID companyId,
                                     @PathVariable UUID accountId) {
        return DataQualityRs.of(service.dataQuality(user.userId(), companyId, accountId));
    }

    @GetMapping("/completeness-ranges")
    public PageRs<CompletenessRs> completenessRanges(CurrentUser user, @PathVariable UUID companyId,
                                                     @PathVariable UUID accountId,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        return PageRs.of(service.completenessRanges(user.userId(), companyId, accountId, query), query,
                CompletenessRs::of);
    }

    static PaymentFilter filter(LocalDate fromDate, LocalDate toDate, Direction direction, String categoryCode,
                                String counterpartyKey, String q, String sort) {
        if (!"operationDate,desc".equals(sort) && !"operationDate,asc".equals(sort)) {
            throw ApiException.unprocessable("INVALID_SORT", "sort: operationDate,asc|operationDate,desc");
        }
        return PaymentFilter.of(fromDate, toDate, direction, categoryCode, counterpartyKey, q, sort);
    }
}