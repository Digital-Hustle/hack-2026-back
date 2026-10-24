package ru.cashflow.orchestrator.api.controller;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.CompanyDtos.ItemsRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.results.application.ResultQueryService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}")
public class ResultQueryController {
    private final ResultQueryService service;

    public ResultQueryController(ResultQueryService service) {
        this.service = service;
    }

    @GetMapping("/forecasts")
    public ItemsRs<JsonNode> forecasts(
            CurrentUser user,
            @PathVariable UUID companyId,
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return new ItemsRs<>(
                service.list(user.userId(), companyId, accountId, false, limit, offset));
    }

    @GetMapping("/forecasts/{id}")
    public JsonNode forecast(
            CurrentUser user,
            @PathVariable UUID companyId,
            @PathVariable UUID accountId,
            @PathVariable UUID id) {
        return service.get(user.userId(), companyId, accountId, id, false);
    }

    @GetMapping("/forecasts/latest")
    public JsonNode latest(
            CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId) {
        return service.list(user.userId(), companyId, accountId, false, 1, 0).stream()
                .findFirst()
                .orElseThrow(ru.cashflow.orchestrator.api.error.ApiException::notFound);
    }

    @GetMapping("/backtests")
    public ItemsRs<JsonNode> backtests(
            CurrentUser user,
            @PathVariable UUID companyId,
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return new ItemsRs<>(
                service.list(user.userId(), companyId, accountId, true, limit, offset));
    }

    @GetMapping("/backtests/{id}")
    public JsonNode backtest(
            CurrentUser user,
            @PathVariable UUID companyId,
            @PathVariable UUID accountId,
            @PathVariable UUID id) {
        return service.get(user.userId(), companyId, accountId, id, true);
    }

    @GetMapping("/dashboard")
    public JsonNode dashboard(
            CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId) {
        return service.dashboard(user.userId(), companyId, accountId);
    }

    @GetMapping("/backtests/{id}/origins")
    public ItemsRs<JsonNode> origins(
            CurrentUser user,
            @PathVariable UUID companyId,
            @PathVariable UUID accountId,
            @PathVariable UUID id) {
        var result = service.get(user.userId(), companyId, accountId, id, true);
        var items = new java.util.ArrayList<JsonNode>();
        result.path("origins").forEach(items::add);
        return new ItemsRs<>(items);
    }
}
