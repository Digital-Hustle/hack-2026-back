package ru.cashflow.orchestrator.integrations;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class TaxIdValidationIntegrationTest extends AbstractIntegrationTest {

    @Test
    void companyInnAndKppAreValidatedByAlgorithmAndLegalForm() throws Exception {
        UUID owner = UUID.randomUUID();
        assertThat(company(owner, "OOO", "7707083893", "773601001").status()).isEqualTo(201);
        assertThat(company(owner, "OOO", "7707083894", "773601001").status()).isEqualTo(422);
        assertThat(company(owner, "OOO", "500100732259", "773601001").status()).isEqualTo(422);
        assertThat(company(owner, "OOO", "7707083893", "000000000").status()).isEqualTo(422);
        assertThat(company(owner, "IP", "500100732259", "").status()).isEqualTo(201);
        assertThat(company(owner, "IP", "7707083893", "").status()).isEqualTo(422);
    }

    @Test
    void companyUpdateRejectsInvalidInnWithoutChangingStoredValue() throws Exception {
        UUID owner = UUID.randomUUID();
        var created = company(owner, "OOO", "7707083893", "773601001");
        String id = created.body().path("id").asText();

        var rejected = send("PUT", "/api/v1/orchestrator/companies/" + id, owner,
                "{\"name\":\"Новое\",\"legalForm\":\"OOO\",\"inn\":\"7707083894\",\"kpp\":\"773601001\","
                        + "\"timezone\":\"Europe/Moscow\"}",
                "If-Match", "\"" + created.body().path("version").asInt() + "\"");
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(send("GET", "/api/v1/orchestrator/companies/" + id, owner, null)
                .body().path("inn").asText()).isEqualTo("7707083893");
    }

    @Test
    void employeeInnIsAlwaysIndividualAndSalaryRecipientUsesTheSameRule() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = UUID.fromString(company(owner, "OOO", "7707083893", "773601001")
                .body().path("id").asText());
        UUID account = createAccount(owner, company, null);

        assertThat(employee(owner, company, "employee-inn-001", "500100732259").status()).isEqualTo(201);
        assertThat(employee(owner, company, "employee-inn-002", "500100732258").status()).isEqualTo(422);
        assertThat(employee(owner, company, "employee-inn-003", "7707083893").status()).isEqualTo(422);

        var created = employee(owner, company, "employee-inn-004", "500100732259");
        String id = created.body().path("id").asText();
        assertThat(created.body().path("inn").asText()).isEqualTo("500100732259");

        var salary = send("PUT", "/api/v1/orchestrator/companies/" + company + "/employees/" + id + "/salary",
                owner, salary(account, "500100732258"), "If-Match", "\"1\"");
        assertThat(salary.status()).isEqualTo(422);
        var accepted = send("PUT", "/api/v1/orchestrator/companies/" + company + "/employees/" + id + "/salary",
                owner, salary(account, "500100732259"), "If-Match", "\"1\"");
        assertThat(accepted.status()).as(accepted.raw()).isEqualTo(200);
    }

    private Response company(UUID owner, String legalForm, String inn, String kpp) throws Exception {
        return send("POST", "/api/v1/orchestrator/companies", owner,
                "{\"name\":\"Тест ИНН\",\"legalForm\":\"" + legalForm + "\",\"inn\":\"" + inn
                        + "\",\"kpp\":\"" + kpp + "\",\"timezone\":\"Europe/Moscow\"}");
    }

    private Response employee(UUID owner, UUID company, String key, String inn) throws Exception {
        return send("POST", "/api/v1/orchestrator/companies/" + company + "/employees", owner,
                "{\"fullName\":\"Иван Иванов\",\"position\":\"Разработчик\",\"inn\":\"" + inn + "\"}",
                "Idempotency-Key", key);
    }

    private static String salary(UUID account, String inn) {
        return "{\"accountId\":\"" + account + "\",\"amount\":\"10000.00\",\"counterpartyInn\":\"" + inn
                + "\",\"schedule\":{\"frequency\":\"MONTHLY\",\"dayOfMonth\":5,\"businessDayRule\":\"NONE\"}}";
    }
}
