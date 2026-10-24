package ru.cashflow.orchestrator.api.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.AssistantDtos.ChatRq;
import ru.cashflow.orchestrator.api.dto.AssistantDtos.ChatRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.assistant.application.AssistantService;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/assistant")
public class AssistantController {

    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    @PostMapping("/chat")
    public ChatRs chat(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                       @Valid @RequestBody ChatRq request) {
        return ChatRs.of(service.chat(user.userId(), companyId, accountId,
                request.question()));
    }
}
