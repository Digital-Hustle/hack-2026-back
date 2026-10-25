package ru.cashflow.orchestrator.api.controller;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.CompanyDtos.ItemsRs;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.results.application.ResultExports;
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

    /** Step-by-step calculation log of a forecast: {@code format=text} (default, file) or {@code format=json}. */
    @GetMapping("/forecasts/{id}/trace")
    public ResponseEntity<?> forecastTrace(
            CurrentUser user,
            @PathVariable UUID companyId,
            @PathVariable UUID accountId,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "text") String format) {
        var result = service.get(user.userId(), companyId, accountId, id, false);
        if ("json".equals(format)) {
            return ResponseEntity.ok(result.path("trace"));
        }
        return file(ResultExports.traceText(result, "Forecast"), "forecast-" + id + "-trace.txt", MediaType.TEXT_PLAIN);
    }

    /** Daily forecast series (point, components, P10/P50/P90, probabilities) as CSV. */
    @GetMapping("/forecasts/{id}/export")
    public ResponseEntity<String> forecastExport(
            CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId, @PathVariable UUID id) {
        var result = service.get(user.userId(), companyId, accountId, id, false);
        return file(ResultExports.forecastCsv(result), "forecast-" + id + ".csv", CSV);
    }

    /** Step-by-step backtest log: experiment, each origin, metrics, benchmarks and model selection. */
    @GetMapping("/backtests/{id}/trace")
    public ResponseEntity<?> backtestTrace(
            CurrentUser user,
            @PathVariable UUID companyId,
            @PathVariable UUID accountId,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "text") String format) {
        var result = service.get(user.userId(), companyId, accountId, id, true);
        if ("json".equals(format)) {
            return ResponseEntity.ok(result.path("trace"));
        }
        return file(ResultExports.traceText(result, "Backtest"), "backtest-" + id + "-trace.txt", MediaType.TEXT_PLAIN);
    }

    /** Forecast vs actual for every origin and day (chart data, confusion-matrix inputs) as CSV. */
    @GetMapping("/backtests/{id}/export")
    public ResponseEntity<String> backtestExport(
            CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId, @PathVariable UUID id) {
        var result = service.get(user.userId(), companyId, accountId, id, true);
        return file(ResultExports.backtestCsv(result), "backtest-" + id + ".csv", CSV);
    }

    /** Human-readable Markdown quality report: metrics, confusion matrices, benchmarks, model choice. */
    @GetMapping("/backtests/{id}/report")
    public ResponseEntity<String> backtestReport(
            CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId, @PathVariable UUID id) {
        var result = service.get(user.userId(), companyId, accountId, id, true);
        return file(ResultExports.backtestReport(result), "backtest-" + id + "-report.md", MARKDOWN);
    }

    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");
    private static final MediaType MARKDOWN = MediaType.parseMediaType("text/markdown;charset=UTF-8");

    private static ResponseEntity<String> file(String body, String name, MediaType type) {
        MediaType withCharset = type.getCharset() == null
                ? new MediaType(type, java.nio.charset.StandardCharsets.UTF_8) : type;
        return ResponseEntity.ok()
                .contentType(withCharset)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(body);
    }
}
