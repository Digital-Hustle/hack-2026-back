package ru.cashflow.orchestrator.integrations;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class RawOperationsImportIntegrationTest extends AbstractIntegrationTest {

    private static final String COMPANIES = "company_id;company_name;company_inn;legal_form;industry;"
            + "first_operation_date;last_operation_date;operations_count\n"
            + "C001;ООО «Тест»;0098524470;ООО;Торговля;2026-01-01;2026-01-05;2\n";
    private static final String OPERATIONS = "company_id;operation_id;operation_date;amount;direction;"
            + "counterparty_inn;counterparty_name;payment_purpose;balance_eod\n"
            + "C001;op1;2026-01-01;1000.00;Поступление;;Клиент;Оплата;11000.00\n"
            + "C001;op2;2026-01-05;400.00;Списание;;Поставщик;Аренда;10600.00\n";

    @Test
    void importConfirmsPeriodSoDaysWithoutRowsAreZerosNotGaps() throws Exception {
        UUID owner = UUID.randomUUID();
        assertThat(upload(owner, "/imports", COMPANIES).statusCode()).isEqualTo(200);
        var result = upload(owner, "/operations/imports", OPERATIONS);
        assertThat(result.statusCode()).isEqualTo(200);
        var item = json.readTree(result.body()).path("items").get(0);
        String history = "/api/v1/orchestrator/companies/" + item.path("companyId").asText() + "/accounts/"
                + item.path("accountId").asText() + "/daily-history?from=2025-12-31&to=2026-01-06";

        var days = send("GET", history, owner, null).body().path("items");
        assertThat(days.get(0).path("status").asText()).isEqualTo("MISSING");
        assertThat(days.get(1).path("status").asText()).isEqualTo("OBSERVED");
        assertThat(days.get(2).path("status").asText()).isEqualTo("ZERO_CONFIRMED");
        assertThat(days.get(5).path("status").asText()).isEqualTo("OBSERVED");
        assertThat(days.get(6).path("status").asText()).isEqualTo("MISSING");

        assertThat(upload(owner, "/operations/imports", OPERATIONS).statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("select count(*) from completeness_ranges").query(Long.class).single()).isEqualTo(1);
    }

    private HttpResponse<String> upload(UUID user, String path, String csv) throws Exception {
        String boundary = "raw-import-boundary";
        String payload = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"data.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + csv + "\r\n--" + boundary + "--\r\n";
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/orchestrator/companies" + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-User-Id", user.toString())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
