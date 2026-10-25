package ru.cashflow.orchestrator.integrations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.knowledge.KnowledgeReader;
import ru.cashflow.orchestrator.features.recurring.application.RecurringService;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.BusinessDayRule;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.DetectedCandidate;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Frequency;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Schedule;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class EmployeesIntegrationTest extends AbstractIntegrationTest {
    @Autowired RecurringService recurring;
    @Autowired CompanyAccess access;
    @Autowired KnowledgeReader knowledge;

    @Test
    void salaryCreationUpdateArchiveAndKnowledgeCutoffAreAtomicAndRevisioned() throws Exception {
        var c = ctx();
        var created = create(c, "employee-create-001");
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        String id = created.body().path("id").asText();
        assertThat(created.body().path("version").asInt()).isEqualTo(1);
        assertThat(create(c, "employee-create-001").body().path("id").asText()).isEqualTo(id);
        assertThat(send("POST", base(c), c.owner(), "{\"fullName\":\"Other\"}",
                "Idempotency-Key", "employee-create-001").status()).isEqualTo(409);
        assertThat(dataVersion(c)).isZero();

        Instant before = clock.instant();
        var salary = send("PUT", base(c) + "/" + id + "/salary", c.owner(), salary(c, "70000.15", null),
                "If-Match", "\"1\"");
        assertThat(salary.status()).as(salary.raw()).isEqualTo(200);
        var p = salary.body().path("recurringPayment");
        assertThat(p.path("amount").asText()).isEqualTo("70000.15");
        assertThat(p.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(p.path("observations").asInt()).isZero();
        assertThat(p.path("firstSeen").isNull()).isTrue();
        assertThat(p.path("employeePayment").asBoolean()).isTrue();
        assertThat(dataVersion(c)).isEqualTo(1);
        var scope = access.requireRead(c.owner(), c.company());
        var date = companyToday(c.company()).minusDays(30);
        assertThat(knowledge.read(scope, c.account(), date, date, before).recurring()).isEmpty();
        assertThat(knowledge.read(scope, c.account(), date, date, clock.instant().plusSeconds(1)).recurring())
                .singleElement().satisfies(pattern -> {
                    assertThat(pattern.amount()).isEqualByComparingTo("70000.15");
                    assertThat(pattern.firstSeen()).isNull();
                    assertThat(pattern.employeePayment()).isTrue();
                });
        Instant afterCreate = clock.instant();

        var changed = send("PUT", base(c) + "/" + id + "/salary", c.owner(), salary(c, "90000.25", 0L),
                "If-Match", "\"2\"");
        assertThat(changed.status()).as(changed.raw()).isEqualTo(200);
        assertThat(changed.body().path("recurringPayment").path("id").asText()).isEqualTo(p.path("id").asText());
        assertThat(send("PUT", base(c) + "/" + id + "/salary", c.owner(), salary(c, "90000.25", 0L),
                "If-Match", "\"3\"").status()).isEqualTo(409);
        assertThat(dataVersion(c)).isEqualTo(2);
        assertThat(knowledge.read(scope, c.account(), date, date, afterCreate).recurring())
                .singleElement().satisfies(pattern -> assertThat(pattern.amount()).isEqualByComparingTo("70000.15"));

        var archived = send("POST", base(c) + "/" + id + "/archive", c.owner(), null, "If-Match", "\"3\"");
        assertThat(archived.status()).as(archived.raw()).isEqualTo(200);
        assertThat(archived.body().path("archived").asBoolean()).isTrue();
        assertThat(archived.body().path("recurringPayment").path("status").asText()).isEqualTo("EXCLUDED");
        assertThat(dataVersion(c)).isEqualTo(3);
        assertThat(send("GET", base(c), c.owner(), null).body().path("items")).isEmpty();
        assertThat(send("GET", base(c) + "?includeArchived=true", c.owner(), null).body().path("items")).hasSize(1);
        assertThat(send("PUT", recurringBase(c) + "/" + p.path("id").asText() + "/status", c.owner(),
                "{\"status\":\"CONFIRMED\"}", "If-Match", "\"2\"").status()).isEqualTo(422);
        assertThat(jdbc.sql("select count(*) from recurring_revisions").query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("select count(*) from payments").query(Long.class).single()).isZero();
    }

    @Test
    void bindingReusesExistingOutflowAndProtectsAgainstDuplicatePayrollAndDetectorOverwrite() throws Exception {
        var c = ctx();
        recurring.mergeDetection(access.requireWrite(c.owner(), c.company()), c.account(), "DET1", 0,
                List.of(candidate(Direction.OUT)));
        var p = send("GET", recurringBase(c), c.owner(), null).body().path("items").get(0);
        String employee = create(c, "employee-bind-001").body().path("id").asText();
        assertThat(send("PUT", base(c) + "/" + employee + "/salary", c.owner(), salary(c, "1.00", null),
                "If-Match", "\"1\"").status()).isEqualTo(409);
        String link = "{\"accountId\":\"%s\",\"recurringPatternId\":\"%s\",\"patternVersion\":0}"
                .formatted(c.account(), p.path("id").asText());
        var bound = send("PUT", base(c) + "/" + employee + "/recurring-payment", c.owner(), link,
                "If-Match", "\"1\"");
        assertThat(bound.status()).as(bound.raw()).isEqualTo(200);
        assertThat(bound.body().path("recurringPayment").path("id").asText()).isEqualTo(p.path("id").asText());
        String second = create(c, "employee-bind-002").body().path("id").asText();
        assertThat(send("PUT", base(c) + "/" + second + "/recurring-payment", c.owner(),
                link.replace("Version\":0", "Version\":1"), "If-Match", "\"1\"").status()).isEqualTo(409);
        assertThat(recurring.mergeDetection(access.requireWrite(c.owner(), c.company()), c.account(), "DET2", 1,
                List.of(candidate(Direction.OUT))).keptUserDecisions()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from recurring_patterns").query(Long.class).single()).isEqualTo(1);
        var detached = send("DELETE", base(c) + "/" + employee + "/recurring-payment", c.owner(), null,
                "If-Match", "\"2\"");
        assertThat(detached.status()).as(detached.raw()).isEqualTo(200);
        assertThat(detached.body().path("recurringPayment").isNull()).isTrue();
        assertThat(send("GET", recurringBase(c), c.owner(), null).body().path("items").get(0)
                .path("status").asText()).isEqualTo("EXCLUDED");
    }

    @Test
    void rolesTenantIsolationValidationVersionsAndPagination() throws Exception {
        var c = ctx();
        UUID viewer = UUID.randomUUID();
        UUID accountant = UUID.randomUUID();
        addMember(c.owner(), c.company(), viewer, "VIEWER");
        addMember(c.owner(), c.company(), accountant, "ACCOUNTANT");
        String id = create(c, "employee-access-001").body().path("id").asText();
        String path = base(c) + "/" + id;
        assertThat(send("GET", path, viewer, null).status()).isEqualTo(200);
        assertThat(send("GET", path, UUID.randomUUID(), null).status()).isEqualTo(404);
        assertThat(send("GET", path, null, null).status()).isEqualTo(401);
        assertThat(send("PUT", path, viewer, "{\"fullName\":\"Changed\"}", "If-Match", "\"1\"").status()).isEqualTo(403);
        assertThat(send("PUT", path, accountant, "{\"fullName\":\"Changed\"}").status()).isEqualTo(428);
        assertThat(send("PUT", path, accountant, "{\"fullName\":\"Changed\"}", "If-Match", "\"7\"").status()).isEqualTo(409);
        assertThat(send("PUT", path, accountant, "{\"fullName\":\"Changed\"}", "If-Match", "\"1\"").status()).isEqualTo(200);
        for (String amount : List.of("0", "-1", "1.001", "100000000000000000")) {
            assertThat(send("PUT", path + "/salary", accountant, salary(c, amount, null),
                    "If-Match", "\"2\"").status()).as(amount).isEqualTo(422);
        }
        assertThat(send("PUT", path + "/salary", accountant, salary(c, "1.00", null).replace("dayOfMonth\":5", "dayOfMonth\":32"),
                "If-Match", "\"2\"").status()).isEqualTo(422);
        var other = ctx();
        assertThat(send("PUT", path + "/salary", accountant,
                salary(c, "1.00", null).replace(c.account().toString(), other.account().toString()),
                "If-Match", "\"2\"").status()).isEqualTo(404);
        assertThat(send("GET", base(c) + "?page=0&size=1", viewer, null).body().path("page").path("total").asInt()).isEqualTo(1);
        assertThat(send("GET", base(c) + "?page=1&size=1", viewer, null).body().path("items")).isEmpty();
        assertThat(dataVersion(c)).isZero();
    }

    private Response create(Ctx c, String key) throws Exception {
        return send("POST", base(c), c.owner(), "{\"fullName\":\"Иван Иванов\",\"position\":\"Разработчик\"}",
                "Idempotency-Key", key);
    }

    private static String salary(Ctx c, String amount, Long version) {
        return "{\"accountId\":\"%s\",\"amount\":\"%s\",\"counterpartyInn\":\"123456789012\","
                .formatted(c.account(), amount)
                + "\"schedule\":{\"frequency\":\"MONTHLY\",\"dayOfMonth\":5,\"businessDayRule\":\"FOLLOWING\"}"
                + (version == null ? "" : ",\"recurringPatternVersion\":" + version) + "}";
    }

    private static DetectedCandidate candidate(Direction direction) {
        return new DetectedCandidate(direction, "SALARY", "inn:123456789012",
                new Schedule(Frequency.MONTHLY, null, 5, BusinessDayRule.FOLLOWING), new BigDecimal("50000.00"), 3,
                LocalDate.parse("2026-01-05"), LocalDate.parse("2026-03-05"));
    }

    private long dataVersion(Ctx c) throws Exception {
        return send("GET", "/api/v1/orchestrator/companies/" + c.company(), c.owner(), null)
                .body().path("dataVersion").asLong();
    }

    private Ctx ctx() throws Exception {
        UUID user = UUID.randomUUID();
        UUID company = createCompany(user, "Employees");
        return new Ctx(user, company, createAccount(user, company, null));
    }

    private static String base(Ctx c) {
        return "/api/v1/orchestrator/companies/" + c.company() + "/employees";
    }

    private static String recurringBase(Ctx c) {
        return "/api/v1/orchestrator/companies/" + c.company() + "/accounts/" + c.account() + "/recurring-patterns";
    }

    private record Ctx(UUID owner, UUID company, UUID account) {
    }
}
