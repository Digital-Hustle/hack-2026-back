package ru.cashflow.orchestrator.api.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.OneCDtos.SyncRq;
import ru.cashflow.orchestrator.api.dto.OneCDtos.SyncRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.integrations.onec.application.OneCDemoService;

/** Imitation of a 1C integration (demo register → expected payments). OWNER/ACCOUNTANT only. */
@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/integrations/onec")
public class OneCController {

    private final OneCDemoService service;

    public OneCController(OneCDemoService service) {
        this.service = service;
    }

    @PostMapping("/sync")
    public SyncRs sync(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                       @RequestBody(required = false) SyncRq rq) {
        SyncRq request = rq == null ? SyncRq.empty() : rq;
        return SyncRs.of(service.sync(user.userId(), companyId, accountId, request.fromDate(),
                Boolean.TRUE.equals(request.dryRun())));
    }
}
