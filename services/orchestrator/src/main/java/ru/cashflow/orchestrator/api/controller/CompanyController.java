package ru.cashflow.orchestrator.api.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.CompanyDtos.AccountRq;
import ru.cashflow.orchestrator.api.dto.CompanyDtos.AccountRs;
import ru.cashflow.orchestrator.api.dto.CompanyDtos.CompanyRq;
import ru.cashflow.orchestrator.api.dto.CompanyDtos.CompanyRs;
import ru.cashflow.orchestrator.api.dto.CompanyDtos.ItemsRs;
import ru.cashflow.orchestrator.api.dto.CompanyDtos.MemberRq;
import ru.cashflow.orchestrator.api.dto.CompanyDtos.MemberRs;
import ru.cashflow.orchestrator.api.dto.CompanyDtos.RoleRq;
import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.companies.application.CompanyService;

@RestController
@RequestMapping("/api/v1/orchestrator/companies")
public class CompanyController {

    private final CompanyService service;

    public CompanyController(CompanyService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CompanyRs> create(CurrentUser user, @Valid @RequestBody CompanyRq rq) {
        var company = service.create(user.userId(), rq.name(), rq.legalForm(), rq.inn(), rq.kpp(), rq.timezone());
        return ResponseEntity.status(HttpStatus.CREATED).body(CompanyRs.of(company));
    }

    @GetMapping
    public ItemsRs<CompanyRs> list(CurrentUser user) {
        return new ItemsRs<>(service.list(user.userId()).stream().map(CompanyRs::of).toList());
    }

    @GetMapping("/{companyId}")
    public CompanyRs get(CurrentUser user, @PathVariable UUID companyId) {
        return CompanyRs.of(service.get(user.userId(), companyId));
    }

    @PutMapping("/{companyId}")
    public CompanyRs update(CurrentUser user, @PathVariable UUID companyId,
                            @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody CompanyRq rq) {
        return CompanyRs.of(service.update(user.userId(), companyId, version(ifMatch), rq.name(), rq.legalForm(),
                rq.inn(), rq.kpp(), rq.timezone()));
    }

    @GetMapping("/{companyId}/members")
    public ItemsRs<MemberRs> members(CurrentUser user, @PathVariable UUID companyId) {
        return new ItemsRs<>(service.members(user.userId(), companyId).stream().map(MemberRs::of).toList());
    }

    @PostMapping("/{companyId}/members")
    public ResponseEntity<MemberRs> addMember(CurrentUser user, @PathVariable UUID companyId,
                                              @Valid @RequestBody MemberRq rq) {
        var member = service.addMember(user.userId(), companyId, rq.userId(), rq.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberRs.of(member));
    }

    @PatchMapping("/{companyId}/members/{memberId}")
    public ResponseEntity<Void> changeRole(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID memberId,
                                           @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody RoleRq rq) {
        service.changeRole(user.userId(), companyId, memberId, rq.role(), version(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{companyId}/members/{memberId}")
    public ResponseEntity<Void> removeMember(CurrentUser user, @PathVariable UUID companyId,
                                             @PathVariable UUID memberId) {
        service.removeMember(user.userId(), companyId, memberId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{companyId}/accounts")
    public ResponseEntity<AccountRs> createAccount(CurrentUser user, @PathVariable UUID companyId,
                                                   @Valid @RequestBody AccountRq rq) {
        var account = service.createAccount(user.userId(), companyId, rq.name(), rq.bankName(), rq.accountNumber(),
                rq.externalCompanyId());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountRs.of(account));
    }

    @GetMapping("/{companyId}/accounts")
    public ItemsRs<AccountRs> accounts(CurrentUser user, @PathVariable UUID companyId) {
        return new ItemsRs<>(service.accounts(user.userId(), companyId).stream().map(AccountRs::of).toList());
    }

    @GetMapping("/{companyId}/accounts/{accountId}")
    public AccountRs account(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId) {
        return AccountRs.of(service.account(user.userId(), companyId, accountId));
    }

    @PutMapping("/{companyId}/accounts/{accountId}")
    public AccountRs updateAccount(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                   @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody AccountRq rq) {
        return AccountRs.of(service.updateAccount(user.userId(), companyId, accountId, version(ifMatch), rq.name(),
                rq.bankName(), rq.accountNumber(), rq.externalCompanyId()));
    }

    static long version(String ifMatch) {
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            long version = Long.parseLong(value);
            if (version < 0) {
                throw new NumberFormatException();
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new ApiException(400, "BAD_IF_MATCH", "If-Match должен содержать версию записи");
        }
    }
}
