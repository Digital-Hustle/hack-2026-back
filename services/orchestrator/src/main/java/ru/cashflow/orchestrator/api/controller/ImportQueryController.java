package ru.cashflow.orchestrator.api.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.ImportDtos.ImportIssueRs;
import ru.cashflow.orchestrator.api.dto.ImportDtos.ImportRs;
import ru.cashflow.orchestrator.api.dto.PageDtos.PageRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.imports.application.ImportService;
import ru.cashflow.orchestrator.features.paging.model.PageQuery;

/** Contract-shaped read endpoints; staging/finalize stay account-scoped because they write account data. */
@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/imports")
public class ImportQueryController {

    private final ImportService service;

    public ImportQueryController(ImportService service) {
        this.service = service;
    }

    @GetMapping
    public PageRs<ImportRs> list(CurrentUser user, @PathVariable UUID companyId, @RequestParam UUID accountId,
                                 @RequestParam(required = false) Integer page,
                                 @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        return PageRs.of(service.list(user.userId(), companyId, accountId, query), query, ImportController::of);
    }

    @GetMapping("/{importId}")
    public ImportRs get(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID importId) {
        return ImportController.of(service.get(user.userId(), companyId, importId));
    }

    @GetMapping("/{importId}/issues")
    public PageRs<ImportIssueRs> issues(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID importId,
                                        @RequestParam(required = false) String severity,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        return PageRs.of(service.issues(user.userId(), companyId, importId, severity, query), query,
                issue -> ImportController.issue(importId, issue));
    }
}