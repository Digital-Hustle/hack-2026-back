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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    LedgerRepository ledger;

    @Test
    void categoryChangeCreatesRevisionAndIncrementsDataVersionWithoutChangingFact() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "C");
        UUID account = createAccount(owner, company, null);
        UUID payment = insertPayment(company, account, "2026-09-01", "1500.00", "OTHER");
        String path = base(company, account) + "/payments/" + payment;

        var changed = send("PUT", path + "/category", owner, "{\"categoryCode\":\"RENT\"}", "If-Match", "\"0\"");

        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.body().path("categoryCode").asText()).isEqualTo("RENT");
        assertThat(changed.body().path("categorySource").asText()).isEqualTo("USER");
        assertThat(changed.body().path("amount").asText()).isEqualTo("1500.00");
        assertThat(changed.body().path("operationDate").asText()).isEqualTo("2026-09-01");
        var revisions = send("GET", path + "/revisions", owner, null).body().path("items");
        assertThat(revisions).hasSize(2);
        assertThat(revisions.get(0).path("categoryCode").asText()).isEqualTo("OTHER");
        assertThat(revisions.get(1).path("categoryCode").asText()).isEqualTo("RENT");
        assertThat(dataVersion(owner, company)).isEqualTo(1);
        assertThat(send("PUT", path + "/category", owner, "{\"categoryCode\":\"SALARY\"}", "If-Match", "\"0\"").status())
                .isEqualTo(409);
    }

    @Test
    void viewerCannotChangeCategoryAndForeignUserSeesNothing() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID company = createCompany(owner, "C");
        UUID account = createAccount(owner, company, null);
        addMember(owner, company, viewer, "VIEWER");
        UUID payment = insertPayment(company, account, "2026-09-01", "10.00", "OTHER");
        String path = base(company, account) + "/payments/" + payment + "/category";

        assertThat(send("PUT", path, viewer, "{\"categoryCode\":\"RENT\"}", "If-Match", "\"0\"").status()).isEqualTo(403);
        assertThat(send("PUT", path, stranger, "{\"categoryCode\":\"RENT\"}", "If-Match", "\"0\"").status()).isEqualTo(404);
        assertThat(send("GET", base(company, account) + "/payments?from=2026-09-01&to=2026-09-30", stranger, null)
                .status()).isEqualTo(404);
        assertThat(dataVersion(owner, company)).isZero();
    }

    @Test
    void completenessAndManualAnchorRebuildProjectionAndAreAppendOnly() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "C");
        UUID account = createAccount(owner, company, null);
        insertPayment(company, account, "2026-09-01", "100.00", "OTHER");
        String history = base(company, account) + "/daily-history?from=2026-09-01&to=2026-09-03";

        assertThat(send("POST", base(company, account) + "/completeness", owner,
                "{\"fromDate\":\"2026-09-01\",\"toDate\":\"2026-09-02\",\"completeness\":\"CONFIRMED_COMPLETE\","
                        + "\"reason\":\"выписка\"}").status()).isEqualTo(201);
        var anchor = send("POST", base(company, account) + "/balance-anchors", owner,
                "{\"date\":\"2026-09-01\",\"balance\":\"500.00\",\"reason\":\"сверка\"}");
        assertThat(anchor.status()).isEqualTo(201);

        var days = send("GET", history, owner, null).body().path("items");
        assertThat(days).hasSize(2);
        assertThat(days.get(0).path("status").asText()).isEqualTo("OBSERVED");
        assertThat(days.get(0).path("inflow").asText()).isEqualTo("100.00");
        assertThat(days.get(0).path("balanceEod").asText()).isEqualTo("500.00");
        assertThat(days.get(1).path("status").asText()).isEqualTo("ZERO_CONFIRMED");
        assertThat(dataVersion(owner, company)).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from completeness_ranges").query(Long.class).single()).isEqualTo(1);

        assertThat(send("POST", base(company, account) + "/completeness", owner,
                "{\"fromDate\":\"2099-01-01\",\"toDate\":\"2099-01-02\",\"completeness\":\"CONFIRMED_COMPLETE\","
                        + "\"reason\":\"будущее\"}").status()).isEqualTo(422);
        assertThat(send("POST", base(company, account) + "/balance-anchors", owner,
                "{\"date\":\"2026-09-01\",\"balance\":\"1.001\",\"reason\":\"x\"}").status()).isEqualTo(422);
    }

    @Test
    void databaseRejectsNonPositiveAmountAndForeignAccountCompanyPair() throws Exception {
        UUID ownerA = UUID.randomUUID();
        UUID companyA = createCompany(ownerA, "A");
        UUID accountA = createAccount(ownerA, companyA, null);
        UUID companyB = createCompany(UUID.randomUUID(), "B");

        assertThatThrownBy(() -> insertPayment(companyA, accountA, "2026-09-01", "0.00", "OTHER"))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> insertPayment(companyB, accountA, "2026-09-01", "1.00", "OTHER"))
                .isInstanceOf(Exception.class);
    }

    private UUID insertPayment(UUID company, UUID account, String date, String amount, String category) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        ledger.insertPayment(new Payment(id, company, account, null, null, null, LocalDate.parse(date),
                new BigDecimal(amount), Direction.IN, null, null, null, null, category, CategorySource.RULE, "R1",
                null, 0, now, now));
        return id;
    }

    private long dataVersion(UUID owner, UUID company) throws Exception {
        return send("GET", "/api/v1/orchestrator/companies/" + company, owner, null).body().path("dataVersion").asLong();
    }

    private static String base(UUID company, UUID account) {
        return "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;
    }
}
