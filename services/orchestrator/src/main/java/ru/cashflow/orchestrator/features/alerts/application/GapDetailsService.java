package ru.cashflow.orchestrator.features.alerts.application;

import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.alerts.command.GapExplanationBuilder;
import ru.cashflow.orchestrator.features.alerts.model.GapDetails;
import ru.cashflow.orchestrator.features.alerts.repository.AlertRepository;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;

@Service
public class GapDetailsService {

    private final AlertRepository alerts;
    private final CompanyAccess access;
    private final CompanyRepository companies;
    private final ObjectMapper json;

    public GapDetailsService(AlertRepository alerts, CompanyAccess access, CompanyRepository companies, ObjectMapper json) {
        this.alerts = alerts;
        this.access = access;
        this.companies = companies;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GapDetails get(UUID user, UUID company, UUID account, UUID forecastId, UUID gapId) {
        var scope = access.requireRead(user, company);
        companies.findAccount(scope, account).orElseThrow(ApiException::notFound);
        var saved = alerts.gapSource(scope, account, forecastId, gapId).orElseThrow(ApiException::notFound);
        long version = companies.findCompany(scope).orElseThrow(ApiException::notFound).dataVersion();
        String staleness = saved.latestForecast() && saved.inputDataVersion() == version ? "CURRENT" : "STALE";
        try {
            return GapExplanationBuilder.build(forecastId, saved.snapshotId(), gapId, json.readTree(saved.snapshot()),
                    json.readTree(saved.response()), staleness, saved.createdAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Saved forecast input or result is not valid JSON", exception);
        }
    }
}
