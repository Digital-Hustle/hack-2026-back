package ru.cashflow.orchestrator.integrations;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyAccessIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/orchestrator/companies";

    @Test
    void creatorBecomesSingleOwnerInSameTransaction() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Кофейня");

        var members = send("GET", BASE + "/" + company + "/members", owner, null);
        assertThat(members.status()).isEqualTo(200);
        assertThat(members.body().path("items")).hasSize(1);
        assertThat(members.body().path("items").get(0).path("role").asText()).isEqualTo("OWNER");
        assertThat(send("GET", BASE + "/" + company, owner, null).body().path("dataVersion").asLong()).isZero();
    }

    @Test
    void userOfCompanyACannotSeeCompanyOrAccountOfCompanyB() throws Exception {
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        UUID companyA = createCompany(ownerA, "A");
        UUID companyB = createCompany(ownerB, "B");
        UUID accountB = createAccount(ownerB, companyB, null);

        assertThat(send("GET", BASE + "/" + companyB, ownerA, null).status()).isEqualTo(404);
        assertThat(send("GET", BASE + "/" + companyB + "/accounts", ownerA, null).status()).isEqualTo(404);
        assertThat(send("GET", BASE + "/" + companyB + "/accounts/" + accountB, ownerA, null).status()).isEqualTo(404);
        assertThat(send("GET", BASE + "/" + companyA + "/accounts/" + accountB, ownerA, null).status()).isEqualTo(404);
        assertThat(send("GET", BASE, ownerA, null).body().path("items")).hasSize(1);
    }

    @Test
    void viewerReadsButCannotWriteAndOnlyOwnerManagesMembers() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        UUID accountant = UUID.randomUUID();
        UUID company = createCompany(owner, "Магазин");
        addMember(owner, company, viewer, "VIEWER");
        addMember(owner, company, accountant, "ACCOUNTANT");

        assertThat(send("GET", BASE + "/" + company + "/accounts", viewer, null).status()).isEqualTo(200);
        assertThat(send("POST", BASE + "/" + company + "/accounts", viewer, "{\"name\":\"x\"}").status())
                .isEqualTo(403);
        assertThat(send("POST", BASE + "/" + company + "/accounts", accountant, "{\"name\":\"x\"}").status())
                .isEqualTo(201);
        assertThat(send("POST", BASE + "/" + company + "/members", accountant,
                "{\"userId\":\"" + UUID.randomUUID() + "\",\"role\":\"VIEWER\"}").status()).isEqualTo(403);
    }

    @Test
    void secondOwnerAndOwnerRemovalAreRejected() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Компания");

        assertThat(send("POST", BASE + "/" + company + "/members", owner,
                "{\"userId\":\"" + UUID.randomUUID() + "\",\"role\":\"OWNER\"}").status()).isEqualTo(422);
        assertThat(send("DELETE", BASE + "/" + company + "/members/" + owner, owner, null).status()).isEqualTo(404);
    }

    @Test
    void missingDuplicatedOrMalformedUserContextIsUnauthorized() throws Exception {
        assertThat(send("GET", BASE, null, null).status()).isEqualTo(401);
        assertThat(send("GET", BASE, null, null, "X-User-Id", "not-a-uuid").status()).isEqualTo(401);
        UUID a = UUID.randomUUID();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + BASE))
                .header("X-User-Id", a.toString()).header("X-User-Id", UUID.randomUUID().toString()).GET().build();
        assertThat(http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void optimisticLockingRejectsStaleVersion() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Версия");

        var first = send("PUT", BASE + "/" + company, owner, "{\"name\":\"Новое\"}", "If-Match", "\"0\"");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().path("version").asLong()).isEqualTo(2);
        assertThat(send("PUT", BASE + "/" + company, owner, "{\"name\":\"Старое\"}", "If-Match", "\"0\"").status())
                .isEqualTo(409);
        assertThat(send("PUT", BASE + "/" + company, owner, "{\"name\":\"Без версии\"}").status()).isEqualTo(428);
    }

    @Test
    void externalCompanyIdIsUniqueInsideCompanyButNotGrantingAccess() throws Exception {
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        UUID companyA = createCompany(ownerA, "A");
        UUID companyB = createCompany(ownerB, "B");

        createAccount(ownerA, companyA, "C001");
        assertThat(send("POST", BASE + "/" + companyA + "/accounts", ownerA,
                "{\"name\":\"dup\",\"externalCompanyId\":\"C001\"}").status()).isEqualTo(409);
        createAccount(ownerB, companyB, "C001");
        assertThat(send("GET", BASE + "/" + companyB + "/accounts", ownerA, null).status()).isEqualTo(404);
    }

    @Test
    void invalidInputIsRejectedWithoutEchoingValues() throws Exception {
        UUID owner = UUID.randomUUID();
        var response = send("POST", BASE, owner, "{\"name\":\"X\",\"inn\":\"123\"}");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.body().path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.raw()).doesNotContain("\"123\"");
        assertThat(send("POST", BASE, owner, "{\"name\":\"X\",\"timezone\":\"Mars/Base\"}").status()).isEqualTo(422);
        assertThat(send("POST", BASE, owner, "{bad").status()).isEqualTo(400);
    }
}
