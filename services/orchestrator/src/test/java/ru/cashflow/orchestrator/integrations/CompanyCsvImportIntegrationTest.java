package ru.cashflow.orchestrator.integrations;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyCsvImportIntegrationTest extends AbstractIntegrationTest {

    private static final String PATH = "/api/v1/orchestrator/companies/imports";
    private static final String CSV = "company_id;company_name;company_inn;legal_form;industry;"
            + "first_operation_date;last_operation_date;operations_count\n"
            + "C001;ООО «Тест»;0098524470;ООО;Торговля;2024-10-01;2026-09-29;10\n"
            + "C002;ИП Тест;003844671674;ИП;Услуги;2024-10-01;2026-09-30;20\n";

    @Test
    void importsCompaniesAsOwnerAndRepeatIsIdempotent() throws Exception {
        UUID owner = UUID.randomUUID();
        var first = upload(owner, CSV);
        assertThat(first.statusCode()).isEqualTo(200);
        var jsonFirst = json.readTree(first.body());
        assertThat(jsonFirst.path("created").asInt()).isEqualTo(2);
        assertThat(jsonFirst.path("companies").get(0).path("externalCompanyId").asText()).isEqualTo("C001");

        UUID companyId = UUID.fromString(jsonFirst.path("companies").get(0).path("companyId").asText());
        var company = send("GET", "/api/v1/orchestrator/companies/" + companyId, owner, null);
        assertThat(company.status()).isEqualTo(200);
        assertThat(company.body().path("inn").asText()).isEqualTo("0098524470");
        assertThat(company.body().path("myRole").asText()).isEqualTo("OWNER");
        assertThat(jdbc.sql("select industry from companies where id = :id")
                .param("id", companyId).query(String.class).single()).isEqualTo("Торговля");
        assertThat(jdbc.sql("select count(*) from accounts").query(Long.class).single()).isZero();

        var second = upload(owner, CSV);
        assertThat(second.statusCode()).isEqualTo(200);
        var jsonSecond = json.readTree(second.body());
        assertThat(jsonSecond.path("created").asInt()).isZero();
        assertThat(jsonSecond.path("existing").asInt()).isEqualTo(2);
        assertThat(jsonSecond.path("companies").get(0).path("companyId").asText())
                .isEqualTo(companyId.toString());
    }

    @Test
    void invalidOrConflictingRowsRollBackEntireBatch() throws Exception {
        UUID owner = UUID.randomUUID();
        var invalid = upload(owner, CSV.replace("003844671674", "INVALID"));
        assertThat(invalid.statusCode()).isEqualTo(422);
        assertThat(jdbc.sql("select count(*) from companies").query(Long.class).single()).isZero();

        assertThat(upload(owner, CSV).statusCode()).isEqualTo(200);
        String changed = CSV.replace("ООО «Тест»", "ООО Другое")
                + "C003;ООО Третий;0012345678;ООО;Услуги;2024-10-01;2026-09-30;2\n";
        assertThat(upload(owner, changed).statusCode()).isEqualTo(409);
        assertThat(jdbc.sql("select count(*) from companies").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void sourceIdsBelongToOwnerAndUntrustedRequestsAreRejected() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(upload(a, CSV).statusCode()).isEqualTo(200);
        assertThat(upload(b, CSV).statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("select count(*) from companies").query(Long.class).single()).isEqualTo(4);
        assertThat(upload(null, CSV).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> upload(UUID user, String csv) throws Exception {
        String boundary = "company-import-boundary";
        String payload = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"companies.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + csv + "\r\n--" + boundary + "--\r\n";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (user != null) {
            request.header("X-User-Id", user.toString());
        }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
