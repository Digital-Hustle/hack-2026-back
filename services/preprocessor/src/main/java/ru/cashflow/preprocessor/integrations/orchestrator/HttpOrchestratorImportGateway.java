package ru.cashflow.preprocessor.integrations.orchestrator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import ru.cashflow.preprocessor.api.dto.response.PreparedStatementRs;

/**
 * Synchronous Orchestrator client. Same shape as Orchestrator's ForecastClient: base URL and timeouts come from
 * configuration, only routing headers are sent, and logs contain paths, statuses and durations but never bodies.
 */
@Component
public class HttpOrchestratorImportGateway implements OrchestratorImportGateway {

    private static final Logger LOG = LoggerFactory.getLogger(HttpOrchestratorImportGateway.class);
    private static final int MAX_RESPONSE_CHARS = 5_000_000;
    private static final String IMPORTS_PATH = "/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/imports";

    private final HttpClient http;
    private final URI baseUrl;
    private final Duration responseTimeout;
    private final ObjectMapper json;

    public HttpOrchestratorImportGateway(@Value("${preprocessor.orchestrator.base-url:http://localhost:8083}")
                                         String baseUrl,
                                         @Value("${preprocessor.orchestrator.connect-timeout:3s}")
                                         Duration connectTimeout,
                                         @Value("${preprocessor.orchestrator.response-timeout:120s}")
                                         Duration responseTimeout,
                                         ObjectMapper json) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.baseUrl = URI.create(normalized);
        this.responseTimeout = responseTimeout;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    @Override
    public ImportUploadResult upload(ImportUploadCommand command) {
        OrchestratorImportRs staged = stage(command);
        if (staged.finalized()) {
            return new ImportUploadResult(staged.toPublic(command), false);
        }
        if (!staged.staged()) {
            throw new OrchestratorProblemException(409, "IMPORT_CLOSED", "Импорт закрыт");
        }
        if (staged.version() == null) {
            throw new OrchestratorUnavailableException("Orchestrator не вернул версию импорта");
        }
        try {
            OrchestratorImportRs finalized = finalizeImport(command, staged);
            if (finalized.finalized()) {
                return new ImportUploadResult(finalized.toPublic(command), true);
            }
            throw new OrchestratorProblemException(409, "IMPORT_INCOMPLETE", "Импорт не завершён");
        } catch (OrchestratorProblemException | OrchestratorUnavailableException exception) {
            return recover(command, staged, exception);
        }
    }

    private ImportUploadResult recover(ImportUploadCommand command, OrchestratorImportRs staged,
                                       RuntimeException original) {
        try {
            URI uri = UriComponentsBuilder.fromUri(baseUrl).path(IMPORTS_PATH)
                    .pathSegment(staged.id().toString())
                    .buildAndExpand(command.companyId(), command.accountId()).encode(StandardCharsets.UTF_8).toUri();
            var request = HttpRequest.newBuilder(uri).timeout(responseTimeout)
                    .header("Accept", "application/json")
                    .header("X-User-Id", command.userId().toString()).GET().build();
            HttpResponse<String> response = send(request, "recover");
            if (response.statusCode() != 200) {
                throw problem(response);
            }
            OrchestratorImportRs current = read(response, command);
            if (!current.id().equals(staged.id())) {
                throw new OrchestratorUnavailableException("Orchestrator вернул другой importId");
            }
            if (current.finalized()) {
                return new ImportUploadResult(current.toPublic(command), false);
            }
        } catch (OrchestratorProblemException | OrchestratorUnavailableException exception) {
            original.addSuppressed(exception);
        }
        // Never cancel after ambiguous finalize: the transaction may have committed.
        throw original;
    }

    private OrchestratorImportRs stage(ImportUploadCommand command) {
        URI uri = UriComponentsBuilder.fromUri(baseUrl).path(IMPORTS_PATH)
                .queryParam("fileName", command.fileName())
                .queryParam("fileSha256", command.fileSha256())
                .queryParam("validationMode", command.validationMode())
                .queryParamIfPresent("completeness", Optional.ofNullable(command.completeness()))
                .queryParamIfPresent("declaredFromDate", Optional.ofNullable(command.declaredFromDate()))
                .queryParamIfPresent("declaredToDate", Optional.ofNullable(command.declaredToDate()))
                .buildAndExpand(command.companyId(), command.accountId()).encode(StandardCharsets.UTF_8).toUri();
        var request = HttpRequest.newBuilder(uri).timeout(responseTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("X-User-Id", command.userId().toString())
                .header("Idempotency-Key", command.idempotencyKey())
                .POST(HttpRequest.BodyPublishers.ofString(body(command.statement()), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = send(request, "stage");
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw problem(response);
        }
        OrchestratorImportRs staged = read(response, command);
        LOG.info("orchestrator_import_staged importId={} status={} rowsStaged={}", staged.id(), staged.status(),
                staged.rowsStaged());
        return staged;
    }

    private OrchestratorImportRs finalizeImport(ImportUploadCommand command, OrchestratorImportRs staged) {
        URI uri = UriComponentsBuilder.fromUri(baseUrl).path(IMPORTS_PATH)
                .pathSegment(staged.id().toString(), "finalize")
                .buildAndExpand(command.companyId(), command.accountId()).encode(StandardCharsets.UTF_8).toUri();
        var request = HttpRequest.newBuilder(uri).timeout(responseTimeout)
                .header("Accept", "application/json")
                .header("X-User-Id", command.userId().toString())
                .header("If-Match", "\"" + staged.version() + "\"")
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = send(request, "finalize");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw problem(response);
        }
        OrchestratorImportRs finalized = read(response, command);
        LOG.info("orchestrator_import_finalized importId={} status={} rowsInserted={}", finalized.id(),
                finalized.status(), finalized.rowsInserted());
        return finalized;
    }

    private String body(PreparedStatementRs statement) {
        try {
            return json.writeValueAsString(statement);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Не удалось сериализовать PreparedStatementRs", exception);
        }
    }

    private HttpResponse<String> send(HttpRequest request, String stage) {
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            LOG.info("orchestrator_import_call stage={} path={} status={} durationMs={}", stage,
                    request.uri().getPath(), response.statusCode(), (System.nanoTime() - start) / 1_000_000);
            if (response.body() != null && response.body().length() > MAX_RESPONSE_CHARS) {
                throw new OrchestratorUnavailableException("Ответ Orchestrator превышает допустимый размер");
            }
            return response;
        } catch (HttpTimeoutException exception) {
            LOG.warn("orchestrator_import_call_timeout stage={}", stage);
            throw new OrchestratorUnavailableException("Orchestrator не ответил вовремя", exception);
        } catch (IOException exception) {
            LOG.warn("orchestrator_import_call_io stage={}", stage);
            throw new OrchestratorUnavailableException("Orchestrator недоступен", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OrchestratorUnavailableException("Вызов Orchestrator прерван", exception);
        }
    }

    private OrchestratorProblemException problem(HttpResponse<String> response) {
        int status = response.statusCode();
        String code = defaultCode(status);
        String detail = "Orchestrator отклонил импорт";
        JsonNode node = null;
        try {
            node = json.readTree(response.body());
            if (node != null && node.isObject()) {
                if (node.path("code").isTextual()) {
                    code = truncate(node.path("code").asText());
                }
                if (node.path("detail").isTextual()) {
                    detail = truncate(node.path("detail").asText());
                }
            }
        } catch (JsonProcessingException exception) {
            LOG.debug("orchestrator_import_problem_unparsed status={}", status);
        }
        return new OrchestratorProblemException(status, code, detail, node);
    }

    private OrchestratorImportRs read(HttpResponse<String> response, ImportUploadCommand command) {
        try {
            JsonNode node = json.readTree(response.body());
            OrchestratorImportRs result;
            if (node != null && node.has("companyId") && node.has("totalRows")) {
                for (String field : new String[] {"totalRows", "acceptedRows", "rejectedRows", "duplicateRows", "warningCount"}) {
                    if (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToInt()
                            || node.path(field).asInt() < 0) {
                        throw new OrchestratorUnavailableException("Orchestrator вернул некорректные счётчики");
                    }
                }
                var value = json.treeToValue(node, ru.cashflow.preprocessor.api.dto.response.ImportRs.class);
                if (!command.companyId().equals(value.companyId()) || value.fileName() == null
                        || value.fileSha256() == null || !value.fileSha256().matches("[0-9a-f]{64}")) {
                    throw new OrchestratorUnavailableException("Orchestrator вернул другой scope импорта");
                }
                String tag = response.headers().firstValue("ETag").orElse(null);
                Long version = null;
                if (tag != null && tag.matches("\"[0-9]{1,18}\"")) {
                    version = Long.valueOf(tag.substring(1, tag.length() - 1));
                }
                result = OrchestratorImportRs.fromPublic(value, version);
            } else {
                result = json.treeToValue(node, OrchestratorImportRs.class);
            }
            if (result == null || result.id() == null || !command.accountId().equals(result.accountId())
                    || (result.version() == null && (result.publicResult() == null || result.staged()))
                    || result.createdAt() == null || result.rowsStaged() < 0
                    || (result.finalized() && (result.rowsInserted() == null
                    || result.rowsAlreadyPresent() == null))
                    || !("STAGED".equals(result.status()) || "FINALIZED".equals(result.status())
                    || "CANCELLED".equals(result.status()) || "STAGING".equals(result.status())
                    || "COMMITTED".equals(result.status()) || "ABORTED".equals(result.status()))) {
                throw new OrchestratorUnavailableException("Orchestrator вернул некорректный импорт");
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw new OrchestratorUnavailableException("Orchestrator вернул некорректный JSON", exception);
        }
    }

    private static String defaultCode(int status) {
        return switch (status) {
            case 400 -> "MALFORMED_REQUEST";
            case 401 -> "AUTH_REQUIRED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 422 -> "VALIDATION_FAILED";
            case 429 -> "RATE_LIMITED";
            default -> "DEPENDENCY_UNAVAILABLE";
        };
    }

    private static String truncate(String detail) {
        return detail.length() <= 2000 ? detail : detail.substring(0, 2000);
    }
}
