package ru.cashflow.orchestrator.integrations;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP/PostgreSQL contract test with an always-on fixture shaped like PreparedStatementRs.
 * PREPROCESSOR_SAMPLE_JSON overrides the fixture with genuine PreProcessor output from one company;
 * no organiser data is committed to the repository.
 */
class PreprocessorContractTest extends AbstractIntegrationTest {

    @Test
    void realPreprocessorJsonIsAcceptedAndFinalized() throws Exception {
        String path = System.getenv("PREPROCESSOR_SAMPLE_JSON");
        String body;
        if (path != null && !path.isBlank()) {
            body = Files.readString(Path.of(path));
        } else {
            try (InputStream fixture = getClass().getResourceAsStream("/fixtures/preprocessor-prepared-contract.json")) {
                if (fixture == null) {
                    throw new IllegalStateException("PreProcessor contract fixture missing");
                }
                body = new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        String external = json.readTree(body).path("operations").get(0).path("companyId").asText();
        int operations = json.readTree(body).path("operations").size();
        int rejected = json.readTree(body).path("report").path("rowsRejected").asInt();
        int duplicates = json.readTree(body).path("report").path("rowsDuplicate").asInt();
        int total = json.readTree(body).path("report").path("rowsTotal").asInt();
        assertThat(total).isEqualTo(operations + rejected + duplicates);

        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Контракт");
        UUID account = createAccount(owner, company, external);
        String base = "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;

        var staged = send("POST", base + "/imports?validationMode=SKIP_INVALID&completeness=UNKNOWN",
                owner, body, "Idempotency-Key", "contract-check-1");
        assertThat(staged.status()).as(staged.raw()).isEqualTo(201);
        assertThat(staged.body().path("rowsStaged").asInt()).isEqualTo(operations);
        assertThat(staged.body().path("totalRows").asInt()).isEqualTo(total);
        assertThat(staged.body().path("rejectedRows").asInt()).isEqualTo(rejected);
        assertThat(staged.body().path("duplicateRows").asInt()).isEqualTo(duplicates);
        var finalized = send("POST", base + "/imports/" + staged.body().path("id").asText() + "/finalize", owner, null,
                "If-Match", "\"0\"");
        assertThat(finalized.status()).as(finalized.raw()).isEqualTo(200);
        assertThat(finalized.body().path("rowsInserted").asInt()).isEqualTo(operations);
        assertThat(finalized.body().path("dataVersion").asLong()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from payments").query(Long.class).single()).isEqualTo(operations);
        assertThat(jdbc.sql("select count(*) from daily_observations where status = 'MISSING'").query(Long.class)
                .single()).isGreaterThan(0);
    }
}
