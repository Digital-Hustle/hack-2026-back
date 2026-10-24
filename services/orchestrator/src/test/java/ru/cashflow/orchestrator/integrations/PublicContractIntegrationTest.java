package ru.cashflow.orchestrator.integrations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class PublicContractIntegrationTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/orchestrator/companies";

    @Autowired
    LedgerRepository ledger;

    @Test
    void companyAndAccountExposeRolePermissionsArchiveAndLatestBalance() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Contract");
        createCompany(owner, "Second");
        UUID account = createAccount(owner, company, "C100");
        insertPayment(company, account, "2026-09-01", "100.00", Direction.IN, "REVENUE_CUSTOMERS", "Клиент",
                new BigDecimal("500.00"));

        var companies = send("GET", BASE + "?page=0&size=1", owner, null);
        assertThat(companies.status()).isEqualTo(200);
        assertThat(companies.body().path("items")).hasSize(1);
        assertThat(companies.body().path("page").path("page").asInt()).isZero();
        assertThat(companies.body().path("page").path("size").asInt()).isEqualTo(1);
        assertThat(companies.body().path("page").path("total").asLong()).isEqualTo(2);
        assertThat(companies.body().path("page").path("hasNext").asBoolean()).isTrue();
        var companyRs = companies.body().path("items").get(0);
        assertThat(companyRs.path("myRole").asText()).isEqualTo("OWNER");
        assertThat(companyRs.path("archived").asBoolean()).isFalse();
        assertThat(companyRs.path("permissions").path("canEditCompany").asBoolean()).isTrue();
        assertThat(companyRs.path("permissions").path("canManageMembers").asBoolean()).isTrue();
        assertThat(companyRs.path("permissions").path("canImport").asBoolean()).isTrue();

        UUID accountant = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        addMember(owner, company, accountant, "ACCOUNTANT");
        addMember(owner, company, viewer, "VIEWER");

        var asAccountant = send("GET", BASE + "/" + company, accountant, null).body();
        assertThat(asAccountant.path("myRole").asText()).isEqualTo("ACCOUNTANT");
        assertThat(asAccountant.path("permissions").path("canEditCompany").asBoolean()).isFalse();
        assertThat(asAccountant.path("permissions").path("canManageMembers").asBoolean()).isFalse();
        assertThat(asAccountant.path("permissions").path("canEditPayments").asBoolean()).isTrue();

        var asViewer = send("GET", BASE + "/" + company, viewer, null).body();
        assertThat(asViewer.path("myRole").asText()).isEqualTo("VIEWER");
        assertThat(asViewer.path("permissions").path("canEditPayments").asBoolean()).isFalse();
        assertThat(asViewer.path("permissions").path("canImport").asBoolean()).isFalse();

        var accounts = send("GET", BASE + "/" + company + "/accounts?page=0&size=10", owner, null);
        assertThat(accounts.status()).isEqualTo(200);
        var accountRs = accounts.body().path("items").get(0);
        assertThat(accountRs.path("archived").asBoolean()).isFalse();
        assertThat(accountRs.path("latestBalance").asText()).isEqualTo("500.00");
        assertThat(accountRs.path("latestBalanceDate").asText()).isEqualTo("2026-09-01");

        var anchor = send("POST", BASE + "/" + company + "/accounts/" + account + "/balance-anchors", owner,
                "{\"date\":\"2026-09-02\",\"balance\":\"700.00\",\"reason\":\"сверка\"}");
        assertThat(anchor.status()).isEqualTo(201);
        var afterAnchor = send("GET", BASE + "/" + company + "/accounts/" + account, owner, null).body();
        assertThat(afterAnchor.path("latestBalance").asText()).isEqualTo("700.00");
        assertThat(afterAnchor.path("latestBalanceDate").asText()).isEqualTo("2026-09-02");
    }

    @Test
    void paymentListFiltersSortsPagesAndDetailMatchesContract() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Payments");
        UUID account = createAccount(owner, company, "C200");
        UUID first = insertPayment(company, account, "2026-09-01", "10.00", Direction.IN, "REVENUE_SBP", "ООО Ромашка",
                null);
        insertPayment(company, account, "2026-09-02", "20.00", Direction.OUT, "SUPPLIERS", "ООО Лютик",
                new BigDecimal("30.00"));
        insertPayment(company, account, "2026-09-03", "30.00", Direction.OUT, "BANK_FEES", "Банк", null);

        var page = send("GET", BASE + "/" + company + "/payments?accountId=" + account
                + "&page=0&size=2&sort=operationDate,asc", owner, null);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().path("items")).hasSize(2);
        assertThat(page.body().path("items").get(0).path("id").asText()).isEqualTo(first.toString());
        assertThat(page.body().path("items").get(0).path("companyId").asText()).isEqualTo(company.toString());
        assertThat(page.body().path("items").get(0).path("allocatedAmount").asText()).isEqualTo("0.00");
        assertThat(page.body().path("items").get(0).path("createdAt").isTextual()).isTrue();
        assertThat(page.body().path("page").path("total").asLong()).isEqualTo(3);
        assertThat(page.body().path("page").path("hasNext").asBoolean()).isTrue();

        var out = send("GET", BASE + "/" + company + "/payments?accountId=" + account
                + "&direction=OUT&page=0&size=50", owner, null).body().path("items");
        assertThat(out).hasSize(2);
        assertThat(out.get(0).path("direction").asText()).isEqualTo("OUT");

        var filtered = send("GET", BASE + "/" + company + "/payments?accountId=" + account
                + "&q=ромашка&page=0&size=50", owner, null).body().path("items");
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).path("id").asText()).isEqualTo(first.toString());

        var detail = send("GET", BASE + "/" + company + "/payments/" + first, owner, null);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().path("accountId").asText()).isEqualTo(account.toString());
        assertThat(detail.body().path("categorySource").asText()).isEqualTo("RULE");
        assertThat(detail.body().path("amount").asText()).isEqualTo("10.00");

        assertThat(send("GET", BASE + "/" + company + "/payments?accountId=" + account + "&size=201", owner, null)
                .status()).isEqualTo(422);
        assertThat(send("GET", BASE + "/" + company + "/payments?accountId=" + account
                + "&sort=amount,asc", owner, null).status()).isEqualTo(422);
    }

    @Test
    void categoriesCounterpartiesAndAtomicBulkChange() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Catalog");
        UUID account = createAccount(owner, company, "C300");
        UUID first = insertPayment(company, account, "2026-09-01", "10.00", Direction.IN, "REVENUE_SBP",
                "ООО Ромашка", null);
        UUID second = insertPayment(company, account, "2026-09-02", "20.00", Direction.OUT, "SUPPLIERS",
                "ООО Лютик", null);
        insertPayment(company, account, "2026-09-03", "30.00", Direction.OUT, "SUPPLIERS", "ООО Лютик", null);

        var categories = send("GET", BASE + "/" + company + "/categories", owner, null);
        assertThat(categories.status()).isEqualTo(200);
        assertThat(categories.body().path("items")).hasSize(31);
        var revenue = find(categories.body().path("items"), "code", "REVENUE_SBP");
        assertThat(revenue.path("directions").get(0).asText()).isEqualTo("IN");
        assertThat(revenue.path("system").asBoolean()).isTrue();
        var salary = find(categories.body().path("items"), "code", "SALARY");
        assertThat(salary.path("directions").get(0).asText()).isEqualTo("OUT");

        var counterparties = send("GET", BASE + "/" + company + "/counterparties?accountId=" + account
                + "&page=0&size=1", owner, null);
        assertThat(counterparties.status()).isEqualTo(200);
        assertThat(counterparties.body().path("items")).hasSize(1);
        assertThat(counterparties.body().path("page").path("total").asLong()).isEqualTo(2);
        var searched = send("GET", BASE + "/" + company + "/counterparties?accountId=" + account
                + "&q=Ромашка", owner, null).body().path("items");
        assertThat(searched).hasSize(1);
        assertThat(searched.get(0).path("name").asText()).isEqualTo("ООО Ромашка");

        String bulk = "{\"categoryCode\":\"RENT\",\"payments\":[{\"paymentId\":\"" + first
                + "\",\"expectedVersion\":1},{\"paymentId\":\"" + second + "\",\"expectedVersion\":1}]}";
        var changed = send("POST", BASE + "/" + company + "/payments/category-changes", owner, bulk,
                "Idempotency-Key", "bulk-category-0001");
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.body().path("items")).hasSize(2);
        assertThat(changed.body().path("items").get(0).path("categoryCode").asText()).isEqualTo("RENT");
        assertThat(changed.body().path("items").get(0).path("version").asLong()).isEqualTo(2);
        assertThat(send("GET", BASE + "/" + company, owner, null).body().path("dataVersion").asLong()).isEqualTo(1);

        var replay = send("POST", BASE + "/" + company + "/payments/category-changes", owner, bulk,
                "Idempotency-Key", "bulk-category-0001");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().path("items")).hasSize(2);
        assertThat(send("GET", BASE + "/" + company, owner, null).body().path("dataVersion").asLong()).isEqualTo(1);

        String other = "{\"categoryCode\":\"SALARY\",\"payments\":[{\"paymentId\":\"" + first
                + "\",\"expectedVersion\":2}]}";
        assertThat(send("POST", BASE + "/" + company + "/payments/category-changes", owner, other,
                "Idempotency-Key", "bulk-category-0001").status()).isEqualTo(409);
        assertThat(send("POST", BASE + "/" + company + "/payments/category-changes", owner, bulk,
                "Idempotency-Key", "bulk-category-0002").status()).isEqualTo(409);

        UUID viewer = UUID.randomUUID();
        addMember(owner, company, viewer, "VIEWER");
        assertThat(send("POST", BASE + "/" + company + "/payments/category-changes", viewer, bulk,
                "Idempotency-Key", "bulk-category-0003").status()).isEqualTo(403);
    }

    @Test
    void archivingIsOwnerOnlyIdempotentAndKeepsArchivedResourcesReadable() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID accountant = UUID.randomUUID();
        UUID company = createCompany(owner, "Archive");
        UUID account = createAccount(owner, company, "C400");
        addMember(owner, company, accountant, "ACCOUNTANT");

        assertThat(send("POST", BASE + "/" + company + "/accounts/" + account + "/archive", accountant, null,
                "If-Match", "\"0\"", "Idempotency-Key", "archive-account-01").status()).isEqualTo(403);

        var archivedAccount = send("POST", BASE + "/" + company + "/accounts/" + account + "/archive", owner, null,
                "If-Match", "\"0\"", "Idempotency-Key", "archive-account-02");
        assertThat(archivedAccount.status()).isEqualTo(200);
        assertThat(archivedAccount.body().path("archived").asBoolean()).isTrue();
        assertThat(archivedAccount.body().path("version").asLong()).isEqualTo(2);
        var replay = send("POST", BASE + "/" + company + "/accounts/" + account + "/archive", owner, null,
                "If-Match", "\"0\"", "Idempotency-Key", "archive-account-02");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().path("archived").asBoolean()).isTrue();

        assertThat(send("PUT", BASE + "/" + company + "/accounts/" + account, owner,
                "{\"name\":\"renamed\"}", "If-Match", "\"1\"").status()).isEqualTo(403);
        assertThat(send("GET", BASE + "/" + company + "/accounts/" + account, owner, null).body()
                .path("archived").asBoolean()).isTrue();
        var accounts = send("GET", BASE + "/" + company + "/accounts", owner, null).body().path("items");
        assertThat(accounts).hasSize(1);
        assertThat(accounts.get(0).path("archived").asBoolean()).isTrue();

        assertThat(send("POST", BASE + "/" + company + "/archive", accountant, null,
                "If-Match", "\"0\"", "Idempotency-Key", "archive-company-01").status()).isEqualTo(403);
        var archived = send("POST", BASE + "/" + company + "/archive", owner, null,
                "If-Match", "\"0\"", "Idempotency-Key", "archive-company-02");
        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.body().path("archived").asBoolean()).isTrue();
        assertThat(archived.body().path("version").asLong()).isEqualTo(2);
        assertThat(send("GET", BASE + "/" + company, owner, null).body().path("archived").asBoolean()).isTrue();
        assertThat(send("POST", BASE + "/" + company + "/accounts", owner, "{\"name\":\"blocked\"}").status())
                .isEqualTo(403);
        assertThat(send("POST", BASE + "/" + company + "/archive", owner, null,
                "If-Match", "\"0\"", "Idempotency-Key", "archive-company-02").status()).isEqualTo(200);
    }

    private UUID insertPayment(UUID company, UUID account, String date, String amount, Direction direction,
                               String category, String counterparty, BigDecimal balanceEod) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        ledger.insertPayment(new Payment(id, company, account, UUID.randomUUID(), null, null,
                LocalDate.parse(date), new BigDecimal(amount), direction, "name:" + counterparty, null, counterparty,
                "Назначение", category, CategorySource.RULE, "R1", balanceEod, 0, now, now));
        return id;
    }

    private static com.fasterxml.jackson.databind.JsonNode find(
            com.fasterxml.jackson.databind.JsonNode items, String field, String value) {
        for (var item : items) {
            if (value.equals(item.path(field).asText())) {
                return item;
            }
        }
        throw new AssertionError("not found: " + value);
    }
}