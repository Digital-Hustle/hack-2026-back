package ru.cashflow.orchestrator.features.alerts.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.alerts.command.ForecastAlertFactory;
import ru.cashflow.orchestrator.features.alerts.model.AlertTypes.AlertView;
import ru.cashflow.orchestrator.features.alerts.model.AlertTypes.SavedAlert;
import ru.cashflow.orchestrator.features.alerts.repository.AlertRepository;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.forecast.model.ForecastResult;

@Service
public class AlertService {

    private final AlertRepository alerts;
    private final CompanyAccess access;
    private final CompanyRepository companies;
    private final Clock clock;

    public AlertService(AlertRepository alerts, CompanyAccess access, CompanyRepository companies, Clock clock) {
        this.alerts = alerts;
        this.access = access;
        this.companies = companies;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPublication(UUID companyId) {
        alerts.lockPublication(companyId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onForecastSaved(ForecastResult result, JsonNode input, JsonNode response, Instant now) {
        alerts.publish(result, ForecastAlertFactory.create(result, input, response), now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<AlertView> list(UUID user, UUID company, UUID account, boolean activeOnly, int limit, int offset) {
        CompanyScope scope = access.requireRead(user, company);
        companies.findAccount(scope, account).orElseThrow(ApiException::notFound);
        if (limit < 1 || limit > 500 || offset < 0) {
            throw ApiException.unprocessable("INVALID_PAGE", "limit 1..500, offset >= 0");
        }
        long version = companies.findCompany(scope).orElseThrow(ApiException::notFound).dataVersion();
        return alerts.list(scope, account, activeOnly, limit, offset).stream().map(a -> view(a, version)).toList();
    }

    @Transactional
    public AlertView acknowledge(UUID user, UUID company, UUID account, UUID alertId) {
        CompanyScope scope = access.requireRead(user, company);
        companies.findAccount(scope, account).orElseThrow(ApiException::notFound);
        alerts.find(scope, account, alertId).orElseThrow(ApiException::notFound);
        if (!scope.role().canWriteFinance()) {
            throw ApiException.forbidden();
        }
        alerts.acknowledge(scope, account, alertId, clock.instant());
        long version = companies.findCompany(scope).orElseThrow(ApiException::notFound).dataVersion();
        return view(alerts.find(scope, account, alertId).orElseThrow(ApiException::notFound), version);
    }

    private static AlertView view(SavedAlert a, long currentVersion) {
        return new AlertView(a.id(), a.companyId(), a.accountId(), a.forecastId(), a.gapId(), a.planId(), a.code(), a.title(),
                a.message(), a.fromDate(), a.toDate(), a.amount() == null ? null : a.amount().toPlainString(), a.probability(),
                a.active(), a.acknowledgedAt(), a.latestForecast() && a.inputDataVersion() == currentVersion ? "CURRENT" : "STALE",
                a.inputDataVersion(), a.createdAt());
    }
}
