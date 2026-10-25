package ru.cashflow.orchestrator.units;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.integrations.bitrix.gateway.HttpBitrixClient;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.BitrixDocument;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.DocumentKind;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Portal;
import ru.cashflow.orchestrator.features.integrations.bitrix.model.BitrixTypes.Stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Talks to a local fake of the Bitrix24 REST API: request shape, paging, stage semantics and error mapping. */
class HttpBitrixClientTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 25);
    private static final LocalDate TO = LocalDate.of(2026, 11, 24);

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private Portal portal;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/1/token123/", this::handle);
        server.start();
        portal = new Portal("test", "http://127.0.0.1:" + server.getAddress().getPort() + "/rest/1/token123/");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsDealsWithPagingInvoicesWithStageSemanticsAndCompanyNames() {
        responses.put("crm.deal.list.json#0", """
                {"result":[{"ID":"10","TITLE":"Поставка","OPPORTUNITY":"350000.00","CURRENCY_ID":"RUB",
                  "BEGINDATE":"2026-10-01T03:00:00+03:00","CLOSEDATE":"2026-10-30T03:00:00+03:00",
                  "STAGE_ID":"EXECUTING","STAGE_SEMANTIC_ID":"P","PROBABILITY":"80","COMPANY_ID":"7"}],
                 "next":50,"total":2}""");
        responses.put("crm.deal.list.json#50", """
                {"result":[{"ID":"11","TITLE":"Лицензии","OPPORTUNITY":75000,"CURRENCY_ID":"RUB",
                  "CLOSEDATE":"2026-11-02T03:00:00+03:00","STAGE_ID":"WON","STAGE_SEMANTIC_ID":"S",
                  "PROBABILITY":null,"COMPANY_ID":"0"}],"total":2}""");
        responses.put("crm.item.list.json#0", """
                {"result":{"items":[
                  {"id":5,"title":"Счёт","opportunity":64000,"currencyId":"RUB","begindate":"2026-10-20T00:00:00+03:00",
                   "closedate":"2026-11-01T00:00:00+03:00","stageId":"DT31_2:CUSTOM","accountNumber":"2026-118","companyId":7},
                  {"id":6,"title":"Старый","opportunity":1000,"currencyId":"RUB",
                   "closedate":"2026-11-03T00:00:00+03:00","stageId":"DT31_2:P","accountNumber":null,"companyId":0}]},
                 "total":2}""");
        responses.put("crm.status.list.json", """
                {"result":[{"STATUS_ID":"DT31_2:CUSTOM","SEMANTICS":null},{"STATUS_ID":"DT31_2:P","SEMANTICS":"S"}]}""");
        responses.put("crm.company.list.json", """
                {"result":[{"ID":"7","TITLE":"ООО «Ромашка»"}]}""");

        List<BitrixDocument> documents = client(20).fetch(portal, FROM, TO, true, true);

        assertThat(documents).hasSize(4);
        BitrixDocument deal = documents.get(0);
        assertThat(deal.kind()).isEqualTo(DocumentKind.DEAL);
        assertThat(deal.amount()).isEqualByComparingTo("350000.00");
        assertThat(deal.dueDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(deal.issuedDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(deal.stage()).isEqualTo(Stage.OPEN);
        assertThat(deal.probability()).isEqualTo(80);
        assertThat(deal.companyName()).isEqualTo("ООО «Ромашка»");
        BitrixDocument won = documents.get(1);
        assertThat(won.stage()).isEqualTo(Stage.WON);
        assertThat(won.probability()).isNull();
        assertThat(won.companyId()).isNull();
        BitrixDocument invoice = documents.get(2);
        assertThat(invoice.kind()).isEqualTo(DocumentKind.INVOICE);
        assertThat(invoice.documentNumber()).isEqualTo("2026-118");
        assertThat(invoice.stage()).isEqualTo(Stage.OPEN);
        assertThat(documents.get(3).stage()).isEqualTo(Stage.WON);
        assertThat(documents.get(3).documentNumber()).isEqualTo("6");

        JsonNode dealRequest = bodies.get(0);
        assertThat(dealRequest.path("filter").path(">=CLOSEDATE").asText()).isEqualTo("2026-10-25");
        assertThat(dealRequest.path("filter").path("<=CLOSEDATE").asText()).isEqualTo("2026-11-24");
        assertThat(bodies.get(2).path("entityTypeId").asInt()).isEqualTo(31);
        assertThat(bodies.get(3).path("filter").path("ENTITY_ID").asText()).isEqualTo("SMART_INVOICE_STAGE_2");
        assertThat(calls).containsExactly("crm.deal.list.json", "crm.deal.list.json", "crm.item.list.json",
                "crm.status.list.json", "crm.company.list.json");
    }

    @Test
    void fallsBackToDefaultInvoiceStagesWhenStatusListFails() {
        responses.put("crm.item.list.json#0", """
                {"result":{"items":[{"id":1,"opportunity":10,"closedate":"2026-11-01T00:00:00+03:00","stageId":"DT31_1:D"},
                  {"id":2,"opportunity":10,"closedate":"2026-11-01T00:00:00+03:00","stageId":"DT31_1:P"}]}}""");
        statuses.put("crm.status.list.json", 500);

        var documents = client(20).fetch(portal, FROM, TO, false, true);

        assertThat(documents.get(0).stage()).isEqualTo(Stage.LOST);
        assertThat(documents.get(1).stage()).isEqualTo(Stage.WON);
    }

    @Test
    void mapsBitrixErrorsToStableCodes() {
        responses.put("crm.deal.list.json#0", "{\"error\":\"INVALID_CREDENTIALS\",\"error_description\":\"bad\"}");
        statuses.put("crm.deal.list.json", 401);
        assertThatThrownBy(() -> client(20).fetch(portal, FROM, TO, true, false))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("BITRIX_AUTH_FAILED"));

        responses.put("crm.deal.list.json#0", "{\"error\":\"QUERY_LIMIT_EXCEEDED\"}");
        statuses.put("crm.deal.list.json", 200);
        assertThatThrownBy(() -> client(20).fetch(portal, FROM, TO, true, false))
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("BITRIX_RATE_LIMITED"));
    }

    @Test
    void refusesPartialListInsteadOfCancellingUnreadDocuments() {
        responses.put("crm.deal.list.json#0", "{\"result\":[],\"next\":50}");
        responses.put("crm.deal.list.json#50", "{\"result\":[],\"next\":100}");

        assertThatThrownBy(() -> client(2).fetch(portal, FROM, TO, true, false))
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("BITRIX_TOO_MANY_DOCUMENTS"));
    }

    private HttpBitrixClient client(int maxPages) {
        return new HttpBitrixClient(json, Duration.ofSeconds(2), Duration.ofSeconds(5), maxPages);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestURI().getPath().substring("/rest/1/token123/".length());
        JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
        calls.add(method);
        bodies.add(body);
        String response = responses.getOrDefault(method + "#" + body.path("start").asInt(),
                responses.getOrDefault(method, "{\"result\":[]}"));
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statuses.getOrDefault(method, 200), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
