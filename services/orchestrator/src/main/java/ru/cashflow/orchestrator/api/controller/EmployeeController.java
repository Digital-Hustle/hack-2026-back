package ru.cashflow.orchestrator.api.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.PageDtos.PageRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.employees.application.EmployeeService;
import ru.cashflow.orchestrator.features.employees.model.EmployeeDraft;
import ru.cashflow.orchestrator.features.employees.model.EmployeeView;
import ru.cashflow.orchestrator.features.employees.model.SalaryDraft;
import ru.cashflow.orchestrator.features.paging.model.PageQuery;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Schedule;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/employees")
public class EmployeeController {
    private final EmployeeService service;

    public EmployeeController(EmployeeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<EmployeeRs> create(CurrentUser user, @PathVariable UUID companyId,
                                             @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody EmployeeRq rq) {
        return ResponseEntity.status(HttpStatus.CREATED).body(EmployeeRs.of(
                service.create(user.userId(), companyId, rq.draft(), key)));
    }

    @GetMapping
    public PageRs<EmployeeRs> list(CurrentUser user, @PathVariable UUID companyId,
                                   @RequestParam(defaultValue = "false") boolean includeArchived,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        return PageRs.of(service.list(user.userId(), companyId, includeArchived, query), query, EmployeeRs::of);
    }

    @GetMapping("/{employeeId}")
    public EmployeeRs get(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID employeeId) {
        return EmployeeRs.of(service.get(user.userId(), companyId, employeeId));
    }

    @PutMapping("/{employeeId}")
    public EmployeeRs update(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID employeeId,
                              @RequestHeader("If-Match") String version, @Valid @RequestBody EmployeeRq rq) {
        return EmployeeRs.of(service.update(user.userId(), companyId, employeeId,
                CompanyController.storedVersion(version), rq.draft()));
    }

    @PutMapping("/{employeeId}/salary")
    public EmployeeRs salary(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID employeeId,
                              @RequestHeader("If-Match") String version, @Valid @RequestBody SalaryRq rq) {
        return EmployeeRs.of(service.salary(user.userId(), companyId, employeeId,
                CompanyController.storedVersion(version), new SalaryDraft(rq.accountId(), rq.amount(), rq.schedule(),
                        rq.counterpartyInn(), rq.counterpartyName(), rq.recurringPatternVersion())));
    }

    @PutMapping("/{employeeId}/recurring-payment")
    public EmployeeRs bind(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID employeeId,
                            @RequestHeader("If-Match") String version, @Valid @RequestBody LinkRq rq) {
        return EmployeeRs.of(service.bind(user.userId(), companyId, employeeId,
                CompanyController.storedVersion(version), rq.accountId(), rq.recurringPatternId(), rq.patternVersion()));
    }

    @DeleteMapping("/{employeeId}/recurring-payment")
    public EmployeeRs unlink(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID employeeId,
                              @RequestHeader("If-Match") String version) {
        return EmployeeRs.of(service.stop(user.userId(), companyId, employeeId,
                CompanyController.storedVersion(version), false));
    }

    @PostMapping("/{employeeId}/archive")
    public EmployeeRs archive(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID employeeId,
                               @RequestHeader("If-Match") String version) {
        return EmployeeRs.of(service.stop(user.userId(), companyId, employeeId,
                CompanyController.storedVersion(version), true));
    }

    public record EmployeeRq(@NotBlank @Size(max = 500) String fullName, @Size(max = 200) String position,
                              @Size(max = 1000) String note) {
        EmployeeDraft draft() {
            return new EmployeeDraft(fullName, position, note);
        }
    }

    public record SalaryRq(@NotNull UUID accountId, @NotNull BigDecimal amount, @NotNull Schedule schedule,
                            @Pattern(regexp = "^([0-9]{10}|[0-9]{12})?$") String counterpartyInn,
                            @Size(max = 500) String counterpartyName, @Min(0) Long recurringPatternVersion) {
    }

    public record LinkRq(@NotNull UUID accountId, @NotNull UUID recurringPatternId, @NotNull @Min(0) Long patternVersion) {
    }

    public record EmployeeRs(UUID id, UUID companyId, String fullName, String position, String note, boolean archived,
                              RecurringController.RecurringPatternRs recurringPayment, long version,
                              Instant createdAt, Instant updatedAt) {
        static EmployeeRs of(EmployeeView view) {
            var e = view.employee();
            return new EmployeeRs(e.id(), e.companyId(), e.fullName(), e.position(), e.note(), e.archived(),
                    view.recurringPayment() == null ? null : RecurringController.RecurringPatternRs.of(view.recurringPayment()),
                    e.version() + 1, e.createdAt(), e.updatedAt());
        }
    }
}
