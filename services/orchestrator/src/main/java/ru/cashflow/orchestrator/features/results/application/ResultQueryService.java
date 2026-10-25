package ru.cashflow.orchestrator.features.results.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.companies.repository.CompanyRepository;
import ru.cashflow.orchestrator.features.jobs.repository.JobRepository;
import ru.cashflow.orchestrator.features.knowledge.KnowledgeReader;
import ru.cashflow.orchestrator.features.ledger.command.DataQualityCalculator;
import ru.cashflow.orchestrator.features.results.repository.ResultQueryRepository;
import ru.cashflow.orchestrator.features.results.repository.ResultQueryRepository.SavedResult;
import ru.cashflow.orchestrator.features.settings.application.SettingsService;
import ru.cashflow.orchestrator.features.alerts.application.AlertService;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ResultQueryService {
    private final CompanyAccess access;
    private final CompanyRepository companies;
    private final ResultQueryRepository results;
    private final JobRepository jobs;
    private final KnowledgeReader knowledge;
    private final SettingsService settings;
    private final ObjectMapper json;
    private final Clock clock;
    private final AlertService alerts;
    private final ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository ledger;
    private final boolean autoRefreshEnabled;

    public ResultQueryService(
            CompanyAccess access,
            CompanyRepository companies,
            ResultQueryRepository results,
            JobRepository jobs,
            KnowledgeReader knowledge,
            SettingsService settings,
            ObjectMapper json,
            Clock clock,
            AlertService alerts,
            ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository ledger,
            @Value("${orchestrator.auto-refresh.enabled:true}") boolean autoRefreshEnabled) {
        this.access = access;
        this.companies = companies;
        this.results = results;
        this.jobs = jobs;
        this.knowledge = knowledge;
        this.settings = settings;
        this.json = json;
        this.clock = clock;
        this.alerts = alerts;
        this.ledger = ledger;
        this.autoRefreshEnabled = autoRefreshEnabled;
    }

    public List<JsonNode> list(
            UUID user, UUID company, UUID account, boolean backtest, int limit, int offset) {
        var scope = access.requireRead(user, company);
        companies.findAccount(scope, account).orElseThrow(ApiException::notFound);
        if (limit < 1 || limit > 500 || offset < 0) {
            throw ApiException.unprocessable("INVALID_PAGE", "limit 1..500, offset >= 0");
        }
        long version =
                companies.findCompany(scope).orElseThrow(ApiException::notFound).dataVersion();
        return results.list(scope, account, backtest, limit, offset).stream()
                .map(result -> response(result, version))
                .toList();
    }

    public JsonNode get(UUID user, UUID company, UUID account, UUID id, boolean backtest) {
        var scope = access.requireRead(user, company);
        companies.findAccount(scope, account).orElseThrow(ApiException::notFound);
        long version =
                companies.findCompany(scope).orElseThrow(ApiException::notFound).dataVersion();
        return response(
                results.find(scope, account, id, backtest).orElseThrow(ApiException::notFound),
                version);
    }

    public JsonNode dashboard(UUID user, UUID companyId, UUID accountId) {
        var scope = access.requireRead(user, companyId);
        companies.findAccount(scope, accountId).orElseThrow(ApiException::notFound);
        var company = companies.findCompany(scope).orElseThrow(ApiException::notFound);
        LocalDate cutoff =
                LocalDate.now(clock.withZone(ZoneId.of(company.timezone()))).minusDays(1);
        var config = settings.current(scope, accountId).settings();
        var state =
                knowledge.read(
                        scope,
                        accountId,
                        cutoff.minusDays(config.windowWeeks() * 7L - 1),
                        cutoff,
                        clock.instant().plusNanos(1),
                        config);
        var latest =
                results.list(scope, accountId, false, 1, 0).stream()
                        .findFirst()
                        .map(result -> response(result, company.dataVersion()))
                        .orElse(null);
        JsonNode backtest =
                latest == null
                        ? null
                        : results.compatibleBacktest(
                                        scope,
                                        accountId,
                                        latest.path("algorithmVersion").asText(),
                                        latest.path("settings").path("windowWeeks").asInt(),
                                        latest.path("scope").asText())
                                .map(result -> response(result, company.dataVersion()))
                                .orElse(null);
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("companyId", companyId.toString());
        dashboard.put("accountId", accountId.toString());
        dashboard.put("asOfDate", cutoff.toString());
        dashboard.put("dataVersion", company.dataVersion());
        // Last closed day with known history: the default asOfDate for a forecast. An archive that ends before
        // yesterday cannot be forecast from yesterday (NO_START_BALANCE), only from this date.
        LocalDate lastDataDate = ledger.lastKnownDay(scope, accountId, cutoff).orElse(null);
        dashboard.put("lastDataDate", lastDataDate == null ? null : lastDataDate.toString());
        dashboard.put("suggestedAsOfDate", (lastDataDate == null ? cutoff : lastDataDate).toString());
        dashboard.put(
                "currentBalance",
                state.startBalance()
                        .map(
                                balance ->
                                        Map.of(
                                                "balance",
                                                balance.balance().toPlainString(),
                                                "date",
                                                cutoff.toString(),
                                                "startBalanceDate",
                                                balance.anchorDate().toString()))
                        .orElse(null));
        dashboard.put("latestForecast", latest);
        dashboard.put("latestBacktest", backtest);
        Map<String, Object> refresh = new LinkedHashMap<>();
        refresh.put("needsRefresh", latest == null
                || latest.path("inputDataVersion").asLong() < company.dataVersion());
        refresh.put("autoRefreshEnabled", autoRefreshEnabled);
        refresh.put("dataVersion", company.dataVersion());
        refresh.put("latestForecastInputVersion", latest == null ? -1 : latest.path("inputDataVersion").asLong());
        dashboard.put("refresh", refresh);
        dashboard.put("activeAlerts", alerts.list(user, companyId, accountId, true, 500, 0));
        dashboard.put(
                "upcomingPayments",
                state.plans().stream()
                        .filter(plan -> plan.remainingAmount().signum() > 0)
                        .sorted(java.util.Comparator.comparing(plan -> plan.dueDate()))
                        .limit(10)
                        .map(
                                plan ->
                                        Map.of(
                                                "id",
                                                plan.id().toString(),
                                                "title",
                                                plan.title(),
                                                "dueDate",
                                                plan.dueDate().toString(),
                                                "remainingAmount",
                                                plan.remainingAmount().toPlainString(),
                                                "direction",
                                                plan.direction().name()))
                        .toList());
        dashboard.put("quality", DataQualityCalculator.quality(state.days()));
        dashboard.put(
                "runningJobs",
                jobs.list(scope, accountId, 100).stream()
                        .filter(
                                job ->
                                        job.status().name().equals("RUNNING")
                                                || job.status().name().equals("QUEUED"))
                        .map(
                                job ->
                                        Map.of(
                                                "id",
                                                job.id().toString(),
                                                "type",
                                                job.type().name(),
                                                "status",
                                                job.status().name(),
                                                "origin",
                                                job.origin().name(),
                                                "progress",
                                                job.progress()))
                        .toList());
        dashboard.put(
                "permissions",
                Map.of(
                        "canRead",
                        true,
                        "canWrite",
                        scope.role().canWriteFinance(),
                        "canManageMembers",
                        scope.role().canManageMembers()));
        return json.valueToTree(dashboard);
    }

    private JsonNode response(SavedResult result, long currentVersion) {
        try {
            ObjectNode node = (ObjectNode) json.readTree(result.payload());
            node.put("createdAt", result.createdAt().toString());
            node.put("staleness", result.latestForecast() && result.inputVersion() == currentVersion ? "CURRENT" : "STALE");
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Stored result is not valid JSON", exception);
        }
    }
}
