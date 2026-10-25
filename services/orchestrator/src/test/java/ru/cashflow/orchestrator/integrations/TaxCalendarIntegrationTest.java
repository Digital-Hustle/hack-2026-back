package ru.cashflow.orchestrator.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

class TaxCalendarIntegrationTest extends AbstractIntegrationTest {

    @Test
    void returnsSeededCalendarSortedByDateWithShiftedWeekendDeadlines() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Tax calendar");

        var response = send("GET", "/api/v1/orchestrator/companies/" + company + "/tax-calendar?year=2026",
                owner, null);

        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        var items = response.body().path("items");
        assertThat(items).hasSize(31);
        assertThat(items.get(0).path("dueDate").asText()).isEqualTo("2026-01-28");
        boolean ipAnnual = false;
        boolean shiftedNovember = false;
        for (var item : items) {
            assertThat(item.has("amount")).isFalse();
            assertThat(item.path("sourceUrl").asText()).startsWith("https://www.nalog.gov.ru/");
            if (item.path("taxCode").asText().equals("USN_ANNUAL") && item.path("audience").asText().equals("IP")) {
                ipAnnual = item.path("dueDate").asText().equals("2026-04-28");
            }
            if (item.path("title").asText().equals("НДС за III квартал, 2/3")) {
                shiftedNovember = item.path("dueDate").asText().equals("2026-11-30");
            }
        }
        assertThat(ipAnnual).isTrue();
        assertThat(shiftedNovember).isTrue();
    }

    @Test
    void outsiderGets404AndOtherYearIsEmpty() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Tax calendar access");
        String path = "/api/v1/orchestrator/companies/" + company + "/tax-calendar";

        assertThat(send("GET", path, UUID.randomUUID(), null).status()).isEqualTo(404);
        assertThat(send("GET", path + "?year=2027", owner, null).body().path("items")).isEmpty();
    }
}
