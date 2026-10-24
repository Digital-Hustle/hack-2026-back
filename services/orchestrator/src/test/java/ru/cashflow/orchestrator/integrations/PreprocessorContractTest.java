package ru.cashflow.orchestrator.integrations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional end-to-end contract check against a real PreProcessor output. Runs only when
 * PREPROCESSOR_SAMPLE_JSON points at a file produced by services/preprocessor for one company; organiser data is
 * never committed to the repository.
 */
class PreprocessorContractTest extends AbstractIntegrationTest {

    @Test
    void realPreprocessorJsonIsAcceptedAndFinalized() throws Exception {
        String path = System.getenv("PREPROCESSOR_SAMPLE_JSON");
        Assumptions.assumeTrue(path != null && Files.isRegularFile(Path.of(path)), "PREPROCESSOR_SAMPLE_JSON not set");
        String body = Files.readString(Path.of(path));
        String external = json.readTree(body).path("operations").get(0).path("companyId").asText();
        int operations = json.readTree(body).path("operations").size();

        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Контракт");
        UUID account = createAccount(owner, company, external);
        String base = "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;

        var staged = send("POST", base + "/imports", owner, body, "Idempotency-Key", "contract-check-1");
        assertThat(staged.status()).as(staged.raw()).isEqualTo(201);
        assertThat(staged.body().path("rowsStaged").asInt()).isEqualTo(operations);
        var finalized = send("POST", base + "/imports/" + staged.body().path("id").asText() + "/finalize", owner, null,
                "If-Match", "\"0\"");
        assertThat(finalized.status()).as(finalized.raw()).isEqualTo(200);
        assertThat(finalized.body().path("rowsInserted").asInt()).isEqualTo(operations);
        assertThat(jdbc.sql("select count(*) from payments").query(Long.class).single()).isEqualTo(operations);
        assertThat(jdbc.sql("select count(*) from daily_observations where status = 'MISSING'").query(Long.class)
                .single()).isGreaterThanOrEqualTo(0);
    }
}
