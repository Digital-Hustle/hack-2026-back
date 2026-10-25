package ru.cashflow.orchestrator.api.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.BitrixDtos.StatusRs;
import ru.cashflow.orchestrator.api.dto.BitrixDtos.SyncRq;
import ru.cashflow.orchestrator.api.dto.BitrixDtos.SyncRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.integrations.bitrix.application.BitrixSyncService;

/** Bitrix24 CRM → expected payments. Sync: OWNER/ACCOUNTANT; status: any member. */
@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/integrations/bitrix24")
public class BitrixController {

    private final BitrixSyncService service;

    public BitrixController(BitrixSyncService service) {
        this.service = service;
    }

    @GetMapping
    public StatusRs status(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId) {
        return StatusRs.of(service.status(user.userId(), companyId, accountId));
    }

    @PostMapping("/sync")
    public SyncRs sync(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                       @Valid @RequestBody(required = false) SyncRq rq) {
        SyncRq request = rq == null ? SyncRq.empty() : rq;
        return SyncRs.of(service.sync(user.userId(), companyId, accountId, request.toOptions()));
    }
}
