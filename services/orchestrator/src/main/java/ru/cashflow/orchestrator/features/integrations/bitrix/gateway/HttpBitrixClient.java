package ru.cashflow.orchestrator.features.integrations.bitrix.gateway;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.BitrixDocument;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.DocumentKind;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Portal;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Stage;

/**
 * Bitrix24 REST client over an incoming webhook. Uses crm.deal.list (deals), crm.item.list with entityTypeId=31
 * (smart invoices), crm.status.list (invoice stage semantics) and crm.company.list (payer names). Pages of 50 are
 * read until {@code next} is absent; more than {@code max-pages} pages is refused instead of syncing a partial list,
 * because a partial list would cancel plans of documents that were simply not read. Logs only the REST method,
 * HTTP status and duration — never the webhook URL (it carries the token) or response bodies.
 */
@Component
public class HttpBitrixClient implements BitrixClient {

    private static final Logger LOG = LoggerFactory.getLogger(HttpBitrixClient.class);
    private static final int MAX_RESPONSE_CHARS = 5_000_000;
    private static final int SMART_INVOICE = 31;
    private static final int COMPANY_CHUNK = 50;

    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration requestTimeout;
    private final int maxPages;

    public HttpBitrixClient(ObjectMapper json,
                            @Value("${orchestrator.bitrix.connect-timeout:5s}") Duration connectTimeout,
                            @Value("${orchestrator.bitrix.request-timeout:15s}") Duration requestTimeout,
                            @Value("${orchestrator.bitrix.max-pages:20}") int maxPages) {
        this.json = json;
        this.requestTimeout = requestTimeout;
        this.maxPages = maxPages;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    @Override
    public List<BitrixDocument> fetch(Portal portal, LocalDate from, LocalDate to, boolean deals, boolean invoices) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Bitrix24 must not be called inside a database transaction");
        }
        List<RawDocument> raw = new ArrayList<>();
        if (deals) {
            raw.addAll(deals(portal, from, to));
        }
        if (invoices) {
            raw.addAll(invoices(portal, from, to));
        }
        Map<String, String> companies = companyNames(portal, raw);
        List<BitrixDocument> documents = new ArrayList<>();
        for (RawDocument r : raw) {
            String companyName = r.companyId() == null ? null : companies.get(r.companyId());
            documents.add(new BitrixDocument(r.kind(), r.id(), r.title(), r.amount(), r.currency(), r.dueDate(),
                    r.issuedDate(), r.stage(), r.stageCode(), r.probability(), r.companyId(), companyName,
                    r.documentNumber()));
        }
        return documents;
    }

    private List<RawDocument> deals(Portal portal, LocalDate from, LocalDate to) {
        ObjectNode body = json.createObjectNode();
        body.putObject("filter").put(">=CLOSEDATE", from.toString()).put("<=CLOSEDATE", to.toString());
        var select = body.putArray("select");
        for (String field : List.of("ID", "TITLE", "OPPORTUNITY", "CURRENCY_ID", "BEGINDATE", "CLOSEDATE", "STAGE_ID",
                "STAGE_SEMANTIC_ID", "PROBABILITY", "COMPANY_ID")) {
            select.add(field);
        }
        body.putObject("order").put("CLOSEDATE", "ASC").put("ID", "ASC");
        List<RawDocument> result = new ArrayList<>();
        for (JsonNode deal : pages(portal, "crm.deal.list", body, false)) {
            String semantic = deal.path("STAGE_SEMANTIC_ID").asText("");
            Stage stage = "S".equals(semantic) ? Stage.WON : "F".equals(semantic) ? Stage.LOST : Stage.OPEN;
            result.add(new RawDocument(DocumentKind.DEAL, deal.path("ID").asText(), text(deal, "TITLE"),
                    money(deal.path("OPPORTUNITY")), text(deal, "CURRENCY_ID"), date(deal.path("CLOSEDATE")),
                    date(deal.path("BEGINDATE")), stage, text(deal, "STAGE_ID"), percent(deal.path("PROBABILITY")),
                    id(deal.path("COMPANY_ID")), deal.path("ID").asText()));
        }
        return result;
    }

    private List<RawDocument> invoices(Portal portal, LocalDate from, LocalDate to) {
        ObjectNode body = json.createObjectNode();
        body.put("entityTypeId", SMART_INVOICE);
        body.putObject("filter").put(">=closedate", from.toString()).put("<=closedate", to.toString());
        var select = body.putArray("select");
        for (String field : List.of("id", "title", "opportunity", "currencyId", "begindate", "closedate", "stageId",
                "accountNumber", "companyId")) {
            select.add(field);
        }
        body.putObject("order").put("closedate", "ASC").put("id", "ASC");
        List<JsonNode> items = pages(portal, "crm.item.list", body, true);
        Set<String> stageCodes = new LinkedHashSet<>();
        items.forEach(item -> stageCodes.add(item.path("stageId").asText("")));
        Map<String, Stage> semantics = invoiceStages(portal, stageCodes);
        List<RawDocument> result = new ArrayList<>();
        for (JsonNode item : items) {
            String stageCode = item.path("stageId").asText("");
            Stage stage = semantics.getOrDefault(stageCode, invoiceStageByCode(stageCode));
            String number = text(item, "accountNumber");
            result.add(new RawDocument(DocumentKind.INVOICE, item.path("id").asText(), text(item, "title"),
                    money(item.path("opportunity")), text(item, "currencyId"), date(item.path("closedate")),
                    date(item.path("begindate")), stage, stageCode, null, id(item.path("companyId")),
                    number == null ? item.path("id").asText() : number));
        }
        return result;
    }

    /** Stage semantics from crm.status.list (SEMANTICS S/F); falls back to default stage codes when unavailable. */
    private Map<String, Stage> invoiceStages(Portal portal, Set<String> stageCodes) {
        Set<String> entities = new LinkedHashSet<>();
        for (String code : stageCodes) {
            int colon = code.indexOf(':');
            if (code.startsWith("DT31_") && colon > 5) {
                entities.add("SMART_INVOICE_STAGE_" + code.substring(5, colon));
            }
        }
        Map<String, Stage> result = new HashMap<>();
        for (String entity : entities) {
            ObjectNode body = json.createObjectNode();
            body.putObject("filter").put("ENTITY_ID", entity);
            try {
                JsonNode statuses = call(portal, "crm.status.list", body).path("result");
                for (JsonNode status : statuses) {
                    String semantics = status.path("SEMANTICS").asText("");
                    result.put(status.path("STATUS_ID").asText(), "S".equals(semantics) ? Stage.WON
                            : "F".equals(semantics) ? Stage.LOST : Stage.OPEN);
                }
            } catch (ApiException exception) {
                LOG.warn("bitrix_status_list_unavailable code={}", exception.code());
            }
        }
        return result;
    }

    /** Default smart invoice stages: N new, S sent, P paid, D declined. */
    static Stage invoiceStageByCode(String stageCode) {
        if (stageCode.endsWith(":P")) {
            return Stage.WON;
        }
        return stageCode.endsWith(":D") ? Stage.LOST : Stage.OPEN;
    }

    private Map<String, String> companyNames(Portal portal, List<RawDocument> documents) {
        List<String> ids = documents.stream().map(RawDocument::companyId).filter(id -> id != null).distinct().toList();
        Map<String, String> names = new HashMap<>();
        for (int start = 0; start < ids.size(); start += COMPANY_CHUNK) {
            ObjectNode body = json.createObjectNode();
            var filter = body.putObject("filter").putArray("@ID");
            ids.subList(start, Math.min(ids.size(), start + COMPANY_CHUNK)).forEach(filter::add);
            body.putArray("select").add("ID").add("TITLE");
            try {
                for (JsonNode company : call(portal, "crm.company.list", body).path("result")) {
                    String title = text(company, "TITLE");
                    if (title != null) {
                        names.put(company.path("ID").asText(), title);
                    }
                }
            } catch (ApiException exception) {
                LOG.warn("bitrix_company_list_unavailable code={}", exception.code());
                return names;
            }
        }
        return names;
    }

    private List<JsonNode> pages(Portal portal, String method, ObjectNode body, boolean itemsWrapped) {
        List<JsonNode> rows = new ArrayList<>();
        int start = 0;
        for (int page = 0; page < maxPages; page++) {
            body.put("start", start);
            JsonNode response = call(portal, method, body);
            JsonNode result = itemsWrapped ? response.path("result").path("items") : response.path("result");
            result.forEach(rows::add);
            if (!response.hasNonNull("next")) {
                return rows;
            }
            start = response.path("next").asInt();
        }
        throw ApiException.unprocessable("BITRIX_TOO_MANY_DOCUMENTS",
                "В Битрикс24 слишком много документов за период; уменьшите горизонт синхронизации");
    }

    private JsonNode call(Portal portal, String method, ObjectNode body) {
        String payload;
        try {
            payload = json.writeValueAsString(body);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        var request = HttpRequest.newBuilder(URI.create(portal.baseUrl() + method + ".json")).timeout(requestTimeout)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException exception) {
            LOG.warn("bitrix_call_timeout method={}", method);
            throw new ApiException(504, "BITRIX_TIMEOUT", "Битрикс24 не ответил вовремя");
        } catch (IOException exception) {
            LOG.warn("bitrix_call_failed method={} error={}", method, exception.getClass().getSimpleName());
            throw new ApiException(502, "BITRIX_UNAVAILABLE", "Не удалось подключиться к Битрикс24");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "BITRIX_UNAVAILABLE", "Запрос к Битрикс24 прерван");
        }
        LOG.info("bitrix_call method={} status={} durationMs={}", method, response.statusCode(),
                (System.nanoTime() - started) / 1_000_000);
        String text = response.body() == null ? "" : response.body();
        if (text.length() > MAX_RESPONSE_CHARS) {
            throw new ApiException(502, "BITRIX_RESPONSE_TOO_LARGE", "Ответ Битрикс24 превышает допустимый размер");
        }
        JsonNode node;
        try {
            node = text.isBlank() ? json.createObjectNode() : json.readTree(text);
        } catch (IOException exception) {
            throw new ApiException(502, "BITRIX_BAD_RESPONSE", "Битрикс24 вернул некорректный ответ");
        }
        String error = node.path("error").asText("");
        int status = response.statusCode();
        if (status == 401 || status == 403 || "INVALID_CREDENTIALS".equals(error) || "NO_AUTH_FOUND".equals(error)
                || "insufficient_scope".equals(error) || "ACCESS_DENIED".equals(error)) {
            throw ApiException.unprocessable("BITRIX_AUTH_FAILED",
                    "Битрикс24 отклонил вебхук: проверьте код и права (нужен доступ crm)");
        }
        if (status == 429 || status == 503 || "QUERY_LIMIT_EXCEEDED".equals(error)) {
            throw new ApiException(503, "BITRIX_RATE_LIMITED", "Битрикс24 ограничил частоту запросов, повторите позже");
        }
        if (status < 200 || status >= 300 || !error.isEmpty()) {
            throw new ApiException(502, "BITRIX_ERROR", "Битрикс24 вернул ошибку при чтении CRM");
        }
        return node;
    }

    static BigDecimal money(JsonNode node) {
        String value = node.asText("").strip();
        if (value.isEmpty() || node.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Bitrix24 returns dates as ISO-8601 date-time with the portal offset; the calendar date is what matters. */
    static LocalDate date(JsonNode node) {
        String value = node.asText("").strip();
        if (value.length() < 10 || node.isNull()) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    static Integer percent(JsonNode node) {
        String value = node.asText("").strip();
        if (value.isEmpty() || node.isNull()) {
            return null;
        }
        try {
            int percent = new BigDecimal(value).intValue();
            return percent < 0 || percent > 100 ? null : percent;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String id(JsonNode node) {
        String value = node.asText("").strip();
        return value.isEmpty() || "0".equals(value) || node.isNull() ? null : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }

    private record RawDocument(DocumentKind kind, String id, String title, BigDecimal amount, String currency,
                               LocalDate dueDate, LocalDate issuedDate, Stage stage, String stageCode,
                               Integer probability, String companyId, String documentNumber) {
    }
}
