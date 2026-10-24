package ru.cashflow.orchestrator.integrations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.knowledge.KnowledgeReader;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;
import ru.cashflow.orchestrator.features.ledger.repository.LedgerRepository;
import ru.cashflow.orchestrator.features.recurring.application.RecurringService;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.BusinessDayRule;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.DetectedCandidate;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Frequency;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Schedule;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;
import ru.cashflow.orchestrator.support.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

@Import(KnowledgeReplayIntegrationTest.ClockConfig.class)
class KnowledgeReplayIntegrationTest extends AbstractIntegrationTest {

    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-09-10T09:00:00Z"));

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    KnowledgeReader reader;
    @Autowired
    CompanyAccess access;
    @Autowired
    LedgerRepository ledger;
    @Autowired
    RecurringService recurring;

    @Test
    void lateCategoryOverridePlanAndConfirmationAreInvisibleAtEarlierCutoff() throws Exception {
        CLOCK.set(Instant.parse("2026-09-05T09:00:00Z"));
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Replay");
        UUID account = createAccount(owner, company, null);
        UUID paymentId = UUID.randomUUID();
        ledger.insertPayment(new Payment(paymentId, company, account, null, null, null, LocalDate.parse("2026-09-01"),
                new BigDecimal("500.00"), Direction.OUT, "inn:1", null, null, null, "OTHER", CategorySource.RULE, "R1",
                null, 0, CLOCK.instant(), CLOCK.instant()));
        var scope = access.requireWrite(owner, company);
        recurring.mergeDetection(scope, account, "DET1", 1, List.of(new DetectedCandidate(Direction.OUT, "RENT",
                "inn:1", new Schedule(Frequency.MONTHLY, null, 1, BusinessDayRule.NONE), new BigDecimal("500.00"), 6,
                LocalDate.parse("2026-04-01"), LocalDate.parse("2026-09-01"))));
        Instant cutoff = Instant.parse("2026-09-06T00:00:00Z");

        CLOCK.set(Instant.parse("2026-09-08T09:00:00Z"));
        String base = "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;
        assertThat(send("PUT", base + "/payments/" + paymentId + "/category", owner, "{\"categoryCode\":\"RENT\"}",
                "If-Match", "\"0\"").status()).isEqualTo(200);
        assertThat(send("POST", base + "/expected-payments", owner, "{\"title\":\"Поздний план\",\"direction\":\"OUT\","
                + "\"amount\":\"100.00\",\"dueDate\":\"2026-09-20\",\"categoryCode\":\"RENT\",\"relation\":\"ADDITIONAL\","
                + "\"source\":\"MANUAL\"}").status()).isEqualTo(201);
        var pattern = send("GET", base + "/recurring-patterns", owner, null).body().path("items").get(0);
        assertThat(send("PUT", base + "/recurring-patterns/" + pattern.path("id").asText() + "/status", owner,
                "{\"status\":\"CONFIRMED\"}", "If-Match", "\"0\"").status()).isEqualTo(200);

        var past = reader.read(access.requireRead(owner, company), account, LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-05"), cutoff);
        assertThat(past.payments()).hasSize(1);
        assertThat(past.payments().get(0).categoryCode()).isEqualTo("OTHER");
        assertThat(past.plans()).isEmpty();
        assertThat(past.recurring()).hasSize(1);
        assertThat(past.recurring().get(0).status().name()).isEqualTo("DETECTED");

        var now = reader.read(access.requireRead(owner, company), account, LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-07"), CLOCK.instant().plusSeconds(1));
        assertThat(now.payments().get(0).categoryCode()).isEqualTo("RENT");
        assertThat(now.plans()).hasSize(1);
        assertThat(now.recurring().get(0).status().name()).isEqualTo("CONFIRMED");
    }

    @Test
    void factsKnownAfterCutoffAndFutureDatedFactsAreExcluded() throws Exception {
        CLOCK.set(Instant.parse("2026-09-05T09:00:00Z"));
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Replay2");
        UUID account = createAccount(owner, company, null);
        insert(company, account, "2026-09-01", Instant.parse("2026-09-02T00:00:00Z"));
        insert(company, account, "2026-09-03", Instant.parse("2026-09-09T00:00:00Z"));
        insert(company, account, "2026-09-07", Instant.parse("2026-09-07T12:00:00Z"));

        var state = reader.read(access.requireRead(owner, company), account, LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-05"), Instant.parse("2026-09-08T00:00:00Z"));
        assertThat(state.payments()).extracting(p -> p.operationDate().toString()).containsExactly("2026-09-01");
    }

    @Test
    void partialSettlementAtCutoffIgnoresLaterAllocation() throws Exception {
        CLOCK.set(Instant.parse("2026-09-05T09:00:00Z"));
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Replay3");
        UUID account = createAccount(owner, company, null);
        String base = "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;
        String planId = send("POST", base + "/expected-payments", owner, "{\"title\":\"Счёт\",\"direction\":\"IN\","
                + "\"amount\":\"1000.00\",\"dueDate\":\"2026-09-30\",\"categoryCode\":\"REVENUE_CUSTOMERS\","
                + "\"relation\":\"ADDITIONAL\",\"source\":\"INVOICE\"}").body().path("id").asText();
        UUID first = insert(company, account, "2026-09-04", CLOCK.instant());
        send("POST", base + "/expected-payments/" + planId + "/allocations", owner,
                "{\"paymentId\":\"" + first + "\",\"amount\":\"400.00\"}");
        Instant cutoff = Instant.parse("2026-09-06T00:00:00Z");

        CLOCK.set(Instant.parse("2026-09-08T09:00:00Z"));
        UUID second = insert(company, account, "2026-09-07", CLOCK.instant());
        send("POST", base + "/expected-payments/" + planId + "/allocations", owner,
                "{\"paymentId\":\"" + second + "\",\"amount\":\"600.00\"}");

        var past = reader.read(access.requireRead(owner, company), account, LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-05"), cutoff);
        assertThat(past.plans()).hasSize(1);
        assertThat(past.plans().get(0).settledAmount()).isEqualByComparingTo("400.00");
        assertThat(past.plans().get(0).remainingAmount()).isEqualByComparingTo("600.00");
        assertThat(past.plans().get(0).status().name()).isEqualTo("PARTIALLY_SETTLED");
    }

    @Test
    void settingsAreTakenAsKnownAtCutoff() throws Exception {
        CLOCK.set(Instant.parse("2026-09-05T09:00:00Z"));
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Replay4");
        UUID account = createAccount(owner, company, null);
        String path = "/api/v1/orchestrator/companies/" + company + "/accounts/" + account + "/forecast-settings";
        assertThat(send("GET", path, owner, null).body().path("windowWeeks").asInt()).isEqualTo(12);
        String body = "{\"windowWeeks\":8,\"horizonDays\":30,\"bufferAmount\":\"5000.00\",\"scenarioCount\":2000,"
                + "\"minCalibrationOrigins\":12,\"calibrationStepDays\":7,\"algorithm\":\"WEEKDAY_MEAN_V1\"}";
        assertThat(send("PUT", path, owner, body, "If-Match", "\"0\"").status()).isEqualTo(200);
        assertThat(send("PUT", path, owner, body.replace("\"windowWeeks\":8", "\"windowWeeks\":9"), "If-Match", "\"1\"")
                .status()).isEqualTo(422);

        var before = reader.read(access.requireRead(owner, company), account, LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-04"), Instant.parse("2026-09-05T00:00:00Z"));
        var after = reader.read(access.requireRead(owner, company), account, LocalDate.parse("2026-09-01"),
                LocalDate.parse("2026-09-04"), Instant.parse("2026-09-06T00:00:00Z"));
        assertThat(before.settings().windowWeeks()).isEqualTo(12);
        assertThat(after.settings().windowWeeks()).isEqualTo(8);
        assertThat(after.settings().bufferAmount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void endOfBusinessDayUsesCompanyTimezone() {
        assertThat(KnowledgeReader.endOfDay(LocalDate.parse("2026-09-05"), "Europe/Moscow"))
                .isEqualTo(Instant.parse("2026-09-05T21:00:00Z"));
        assertThat(KnowledgeReader.endOfDay(LocalDate.parse("2026-09-05"), "UTC"))
                .isEqualTo(LocalDate.parse("2026-09-06").atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private UUID insert(UUID company, UUID account, String date, Instant knownAt) {
        UUID id = UUID.randomUUID();
        ledger.insertPayment(new Payment(id, company, account, null, null, null, LocalDate.parse(date),
                new BigDecimal("1000.00"), Direction.IN, null, null, null, null, "OTHER", CategorySource.RULE, "R1",
                null, 0, knownAt, knownAt));
        return id;
    }
}
