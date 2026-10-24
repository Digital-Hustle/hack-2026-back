package ru.cashflow.orchestrator.integrations;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import ru.cashflow.orchestrator.features.forecast.application.ForecastResultHandler;
import ru.cashflow.orchestrator.features.jobs.repository.JobRepository;
import ru.cashflow.orchestrator.features.snapshot.repository.SnapshotRepository;
import ru.cashflow.orchestrator.features.snapshot.command.Fingerprint;
import ru.cashflow.orchestrator.support.AbstractIntegrationTest;
import ru.cashflow.orchestrator.support.ForecastResponses;
import ru.cashflow.orchestrator.support.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(ForecastAlertsIntegrationTest.ClockConfig.class)
class ForecastAlertsIntegrationTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-25T09:00:00Z");
    private static final LocalDate AS_OF = LocalDate.parse("2026-10-24");
    private static final UUID CASH_GAP = UUID.fromString("91000000-0000-4000-8000-000000000001");
    private static final UUID BUFFER_GAP = UUID.fromString("91000000-0000-4000-8000-000000000002");
    private static final UUID EXPENSE_PLAN = UUID.fromString("92000000-0000-4000-8000-000000000001");
    private static final UUID INCOME_PLAN = UUID.fromString("92000000-0000-4000-8000-000000000002");
    private static final UUID OVERDUE_PLAN = UUID.fromString("92000000-0000-4000-8000-000000000003");
    private static final MutableClock CLOCK = new MutableClock(NOW);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock alertsClock() {
            return CLOCK;
        }
    }

    @Autowired ForecastResultHandler handler;
    @Autowired JobRepository jobs;
    @Autowired SnapshotRepository snapshots;

    @Test
    void savedGapIdsAreSharedByForecastStorageAlertsAndDetailsAndProbabilityStaysNull() throws Exception {
        var account = account();
        var calculation = calculation(account, AS_OF, 0, NOW.minusSeconds(30));
        save(calculation, true);

        var response = send("GET", base(account) + "/alerts", account.viewer(), null);
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        var alerts = response.body().path("items");
        var cash = alert(alerts, "CASH_GAP");
        assertThat(cash.path("gapId").asText()).isEqualTo(CASH_GAP.toString());
        assertThat(cash.path("forecastId").asText()).isEqualTo(calculation.result().toString());
        assertThat(cash.path("active").asBoolean()).isTrue();
        assertThat(cash.path("acknowledgedAt").isNull()).isTrue();
        assertThat(cash.path("probability").isNull()).isTrue();
        assertThat(alert(alerts, "BUFFER_BREACH").path("gapId").asText()).isEqualTo(BUFFER_GAP.toString());
        assertThat(alert(alerts, "OVERDUE_PLAN").path("planId").asText()).isEqualTo(OVERDUE_PLAN.toString());
        assertThat(alerts.findValuesAsText("code")).contains("DATA_QUALITY");
        assertThat(jdbc.sql("select id from forecast_gaps where forecast_id=:id and kind='CASH_GAP'")
                .param("id", calculation.result()).query(UUID.class).single()).isEqualTo(CASH_GAP);

        var forecast = send("GET", base(account) + "/forecasts/" + calculation.result(), account.viewer(), null);
        assertThat(forecast.body().path("gaps").get(0).path("id").asText()).isEqualTo(CASH_GAP.toString());
        var detail = details(account, calculation.result(), CASH_GAP);
        assertThat(detail.status()).as(detail.raw()).isEqualTo(200);
        assertThat(detail.body().path("gapId").asText()).isEqualTo(CASH_GAP.toString());
        assertThat(detail.body().path("gap").path("probability").isNull()).isTrue();
        assertThat(detail.body().path("mandatoryOutflows").get(0).path("amount").asText()).isEqualTo("1600.00");
        assertThat(detail.body().path("expectedInflows")).isNotEmpty();
        assertThat(detail.body().path("overduePlans").get(0).path("id").asText()).isEqualTo(OVERDUE_PLAN.toString());
        assertThat(detail.body().path("dataLimitations").findValuesAsText("code")).contains("RISK_UNAVAILABLE");
        assertThat(detail.body().path("recommendations").findValuesAsText("code"))
                .contains("CHECK_PLAN_DATE", "CLARIFY_INFLOW", "REVIEW_VARIABLE_EXPENSES");
        var dashboard = send("GET", base(account) + "/dashboard", account.viewer(), null);
        assertThat(dashboard.body().path("activeAlerts").findValuesAsText("id")).contains(cash.path("id").asText());
        assertThat(dashboard.body().path("activeAlerts").get(0).has("acknowledgedAt")).isTrue();
    }

    @Test
    void acknowledgementIsIdempotentAndMeansSeenNotResolved() throws Exception {
        var account = account();
        save(calculation(account, AS_OF, 0, NOW.minusSeconds(30)), true);
        String id = alert(list(account), "CASH_GAP").path("id").asText();
        String path = base(account) + "/alerts/" + id + "/acknowledge";

        var first = send("POST", path, account.owner(), null);
        assertThat(first.status()).as(first.raw()).isEqualTo(200);
        assertThat(first.body().path("acknowledgedAt").isNull()).isFalse();
        CLOCK.set(NOW.plusSeconds(10));
        var repeated = send("POST", path, account.accountant(), null);
        assertThat(repeated.status()).isEqualTo(200);
        assertThat(repeated.body().path("acknowledgedAt")).isEqualTo(first.body().path("acknowledgedAt"));
        assertThat(repeated.body().path("active").asBoolean()).isTrue();
        assertThat(list(account).findValuesAsText("id").stream().filter(id::equals).count()).isEqualTo(1);
        assertThat(jdbc.sql("select data_version from companies where id=:id").param("id", account.company())
                .query(Long.class).single()).isZero();
    }

    @Test
    void viewerOnlyReadsAndAllResourceScopesAreCheckedBeforeAcknowledgement() throws Exception {
        var account = account();
        var calculation = calculation(account, AS_OF, 0, NOW.minusSeconds(30));
        save(calculation, true);
        String id = alert(list(account), "CASH_GAP").path("id").asText();
        String acknowledge = base(account) + "/alerts/" + id + "/acknowledge";
        assertThat(send("POST", acknowledge, account.viewer(), null).status()).isEqualTo(403);
        assertThat(send("GET", base(account) + "/alerts", null, null).status()).isEqualTo(401);
        assertThat(send("GET", base(account) + "/alerts", UUID.randomUUID(), null).status()).isEqualTo(404);
        assertThat(send("POST", acknowledge, UUID.randomUUID(), null).status()).isEqualTo(404);
        assertThat(send("POST", base(account) + "/alerts/" + UUID.randomUUID() + "/acknowledge", account.viewer(), null)
                .status()).isEqualTo(404);

        var other = account();
        UUID secondAccount = createAccount(account.owner(), account.company(), null);
        for (String target : List.of(base(other), base(account).replace(account.account().toString(), secondAccount.toString()))) {
            assertThat(send("POST", target + "/alerts/" + id + "/acknowledge", account.owner(), null).status()).isEqualTo(404);
            assertThat(send("GET", target + "/forecasts/" + calculation.result() + "/gaps/" + CASH_GAP,
                    account.owner(), null).status()).isEqualTo(404);
        }
        assertThat(details(account, UUID.randomUUID(), CASH_GAP).status()).isEqualTo(404);
        assertThat(details(account, calculation.result(), UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(send("GET", base(account) + "/alerts?limit=501", account.owner(), null).status()).isEqualTo(422);
    }

    @Test
    void newCurrentForecastDeactivatesPreviousAlertsIncludingAcknowledgedOnes() throws Exception {
        var account = account();
        var old = calculation(account, AS_OF, 0, NOW.minusSeconds(30));
        save(old, true);
        String id = alert(list(account), "CASH_GAP").path("id").asText();
        send("POST", base(account) + "/alerts/" + id + "/acknowledge", account.owner(), null);
        var current = calculation(account, AS_OF, 0, NOW.minusSeconds(10));
        save(current, true);

        assertThat(list(account).findValuesAsText("forecastId")).contains(old.result().toString(), current.result().toString());
        for (JsonNode a : list(account)) {
            assertThat(a.path("active").asBoolean()).isEqualTo(a.path("forecastId").asText().equals(current.result().toString()));
        }
        assertThat(jdbc.sql("select count(*) from forecast_gaps where id=:id").param("id", CASH_GAP)
                .query(Long.class).single()).isEqualTo(2);
        assertThat(details(account, old.result(), CASH_GAP).body().path("staleness").asText()).isEqualTo("STALE");
    }

    @Test
    void aNewForecastWithoutGapsStillDeactivatesOldGapAlerts() throws Exception {
        var account = account();
        var old = calculation(account, AS_OF, 0, NOW.minusSeconds(30));
        save(old, true);
        var current = calculation(account, AS_OF, 0, NOW.minusSeconds(10));
        save(current, false);

        var dashboard = send("GET", base(account) + "/dashboard", account.owner(), null).body();
        assertThat(dashboard.path("latestForecast").path("id").asText()).isEqualTo(current.result().toString());
        assertThat(dashboard.path("activeAlerts").findValuesAsText("code")).doesNotContain("CASH_GAP", "BUFFER_BREACH");
        assertThat(list(account).findValuesAsText("forecastId")).contains(old.result().toString());
    }

    @Test
    void lateOldCalculationDoesNotReactivateAlertsOrMoveDashboardBackwards() throws Exception {
        var account = account();
        var old = calculation(account, AS_OF, 0, NOW.minusSeconds(30));
        var current = calculation(account, AS_OF, 0, NOW.minusSeconds(10));
        save(current, true);
        CLOCK.set(NOW.plusSeconds(10));
        save(old, true);

        var dashboard = send("GET", base(account) + "/dashboard", account.owner(), null).body();
        assertThat(dashboard.path("latestForecast").path("id").asText()).isEqualTo(current.result().toString());
        assertThat(dashboard.path("activeAlerts").findValuesAsText("forecastId"))
                .containsOnly(current.result().toString());
        assertThat(send("GET", base(account) + "/forecasts/" + old.result(), account.owner(), null).body()
                .path("staleness").asText()).isEqualTo("STALE");
    }

    @Test
    void lateStaleDataVersionNeverPublishesCurrentAlerts() throws Exception {
        var account = account();
        var old = calculation(account, AS_OF, 0, NOW.minusSeconds(30));
        jdbc.sql("update companies set data_version=1 where id=:id").param("id", account.company()).update();
        var current = calculation(account, AS_OF, 1, NOW.minusSeconds(10));
        save(current, true);
        save(old, true);

        for (JsonNode a : list(account)) {
            assertThat(a.path("active").asBoolean()).isEqualTo(a.path("forecastId").asText().equals(current.result().toString()));
        }
        assertThat(details(account, old.result(), CASH_GAP).body().path("staleness").asText()).isEqualTo("STALE");
    }

    @Test
    void detailsUseSavedInputsAndRemainStableAfterTodaysPlanAndCategoryChanges() throws Exception {
        var account = account();
        var calculation = calculation(account, AS_OF, 0, NOW.minusSeconds(30));
        save(calculation, true);
        var before = details(account, calculation.result(), CASH_GAP).body();
        jdbc.sql("update companies set data_version=data_version+1 where id=:id").param("id", account.company()).update();
        var added = send("POST", base(account) + "/expected-payments", account.owner(),
                "{\"title\":\"Сегодняшняя правка\",\"direction\":\"OUT\",\"amount\":\"9999.00\","
                        + "\"dueDate\":\"2026-11-01\",\"categoryCode\":\"OTHER\",\"relation\":\"ADDITIONAL\",\"source\":\"MANUAL\"}");
        assertThat(added.status()).isEqualTo(201);
        var changed = send("PUT", base(account) + "/expected-payments/" + added.body().path("id").asText(), account.owner(),
                "{\"title\":\"Изменённый план\",\"direction\":\"OUT\",\"amount\":\"8888.00\","
                        + "\"dueDate\":\"2026-11-10\",\"categoryCode\":\"RENT\",\"relation\":\"ADDITIONAL\",\"source\":\"MANUAL\"}",
                "If-Match", "\"0\"");
        assertThat(changed.status()).isEqualTo(200);

        var after = details(account, calculation.result(), CASH_GAP).body();
        assertThat(after.path("staleness").asText()).isEqualTo("STALE");
        for (String field : List.of("gap", "mandatoryOutflows", "expectedInflows", "overduePlans", "dataLimitations", "recommendations")) {
            assertThat(after.path(field)).as(field).isEqualTo(before.path(field));
        }
        assertThat(after.toString()).doesNotContain("Сегодняшняя правка");
    }

    @Test
    void simultaneousAcknowledgementsKeepOneTimestampAndDoNotResolveTheAlert() throws Exception {
        var account = account();
        var calculation = calculation(account, AS_OF, 0, NOW.minusSeconds(30));
        save(calculation, true);
        String alertId = alert(list(account), "CASH_GAP").path("id").asText();
        String path = base(account) + "/alerts/" + alertId + "/acknowledge";
        int originalCount = list(account).size();

        try (var pool = Executors.newFixedThreadPool(4)) {
            Callable<Response> owner = () -> send("POST", path, account.owner(), null);
            Callable<Response> accountant = () -> send("POST", path, account.accountant(), null);
            var responses = pool.invokeAll(List.of(owner, accountant, owner, accountant));
            for (var response : responses) {
                assertThat(response.get().status()).isEqualTo(200);
                assertThat(response.get().body().path("acknowledgedAt").asText()).isEqualTo(NOW.toString());
                assertThat(response.get().body().path("active").asBoolean()).isTrue();
            }
        }
        assertThat(list(account)).hasSize(originalCount);
    }

    @Test
    void simultaneousCompletionsPreferEnqueueOrderWhenDatesVersionsAndTimestampsMatch() throws Exception {
        var account = account();
        var old = calculation(account, AS_OF, 0, NOW.minusSeconds(30));
        var current = calculation(account, AS_OF, 0, NOW.minusSeconds(30));

        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Void> a = () -> saveAndReturn(old);
            Callable<Void> b = () -> saveAndReturn(current);
            for (var completion : pool.invokeAll(List.of(a, b))) {
                completion.get();
            }
        }
        var dashboard = send("GET", base(account) + "/dashboard", account.viewer(), null).body();
        assertThat(dashboard.path("latestForecast").path("id").asText()).isEqualTo(current.result().toString());
        assertThat(dashboard.path("activeAlerts").findValuesAsText("forecastId")).containsOnly(current.result().toString());
        assertThat(jdbc.sql("select count(*) from forecasts").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void failureWhileCreatingAlertsRollsBackTheForecastAndJobSuccessTogether() throws Exception {
        var account = account();
        var calculation = calculation(account, AS_OF, 0, NOW.minusSeconds(30));
        jdbc.sql("alter table alerts add constraint test_reject_quality check (code <> 'DATA_QUALITY')").update();
        try {
            assertThatThrownBy(() -> save(calculation, true))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.sql("select count(*) from forecasts").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("select count(*) from forecast_days").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("select count(*) from forecast_gaps").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("select count(*) from alerts").query(Long.class).single()).isZero();
            assertThat(jobs.findById(calculation.job()).orElseThrow().status().name()).isEqualTo("RUNNING");
        } finally {
            jdbc.sql("alter table alerts drop constraint test_reject_quality").update();
        }
    }

    @Test
    void anOlderBusinessDateCannotSupersedeTheNewerForecastEvenIfItsJobWasQueuedLater() throws Exception {
        var account = account();
        var current = calculation(account, AS_OF, 0, NOW.minusSeconds(30));
        save(current, true);
        var oldDate = calculation(account, AS_OF.minusDays(1), 0, NOW.minusSeconds(10));
        save(oldDate, true);

        var dashboard = send("GET", base(account) + "/dashboard", account.viewer(), null).body();
        assertThat(dashboard.path("latestForecast").path("id").asText()).isEqualTo(current.result().toString());
        assertThat(dashboard.path("activeAlerts").findValuesAsText("forecastId")).containsOnly(current.result().toString());
    }

    @Test
    void aStaleResultSavedBeforeAnyCurrentResultHasOnlyInactiveAlerts() throws Exception {
        var account = account();
        var stale = calculation(account, AS_OF, 0, NOW.minusSeconds(30));
        jdbc.sql("update companies set data_version=1 where id=:id").param("id", account.company()).update();
        save(stale, true);

        assertThat(list(account)).isNotEmpty();
        for (JsonNode a : list(account)) {
            assertThat(a.path("active").asBoolean()).isFalse();
            assertThat(a.path("staleness").asText()).isEqualTo("STALE");
        }
        assertThat(send("GET", base(account) + "/alerts?activeOnly=true", account.viewer(), null).body().path("items"))
                .isEmpty();
    }

    @Test
    void repeatedResponseDoesNotCreateAnotherForecastGapOrAlert() throws Exception {
        var account = account();
        var calculation = calculation(account, AS_OF, 0, NOW.minusSeconds(30));
        save(calculation, true);
        int count = list(account).size();

        assertThatThrownBy(() -> save(calculation, true)).isInstanceOf(ForecastResultHandler.LeaseLostException.class);
        assertThat(list(account)).hasSize(count);
        assertThat(jdbc.sql("select count(*) from forecasts").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from forecast_gaps").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void expiredWorkerStoresNeitherForecastNorAlerts() throws Exception {
        var account = account();
        var calculation = calculation(account, AS_OF, 0, NOW.minusSeconds(30));
        jdbc.sql("update jobs set lease_until=:expired where id=:id")
                .param("expired", Timestamp.from(NOW.minusSeconds(1))).param("id", calculation.job()).update();

        assertThatThrownBy(() -> save(calculation, true)).isInstanceOf(ForecastResultHandler.LeaseLostException.class);
        assertThat(send("GET", base(account) + "/alerts", account.owner(), null).body().path("items")).isEmpty();
        assertThat(jdbc.sql("select count(*) from forecasts").query(Long.class).single()).isZero();
    }

    private AccountContext account() throws Exception {
        CLOCK.set(NOW);
        UUID owner = UUID.randomUUID();
        UUID company = createCompany(owner, "Предупреждения");
        UUID account = createAccount(owner, company, null);
        UUID viewer = UUID.randomUUID();
        UUID accountant = UUID.randomUUID();
        addMember(owner, company, viewer, "VIEWER");
        addMember(owner, company, accountant, "ACCOUNTANT");
        return new AccountContext(owner, viewer, accountant, company, account);
    }

    private Calculation calculation(AccountContext account, LocalDate asOf, long version, Instant created) throws Exception {
        UUID snapshot = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID result = UUID.randomUUID();
        ObjectNode input = json.createObjectNode();
        input.put("snapshotId", snapshot.toString()).put("companyId", account.company().toString())
                .put("accountId", account.account().toString()).put("asOfDate", asOf.toString())
                .put("algorithmVersion", "WEEKDAY_MEAN_V1").put("inputDataVersion", version)
                .put("scope", "WITH_KNOWN_PLANS").put("startBalance", "1000.00").put("startBalanceDate", asOf.toString());
        input.putObject("settings").put("bufferAmount", "100.00").put("windowWeeks", 12).put("horizonDays", 30)
                .put("scenarioCount", 2000).put("minCalibrationOrigins", 12).put("calibrationStepDays", 7)
                .put("algorithm", "WEEKDAY_MEAN_V1");
        input.putArray("calibrationOrigins");
        input.putArray("recurringPatterns");
        input.put("historyFrom", asOf.minusDays(2).toString());
        input.putArray("payments");
        input.putArray("completeRanges");
        input.putArray("dailyHistory").addObject().put("date", asOf.minusDays(2).toString()).put("status", "MISSING")
                .putNull("inflow").putNull("outflow").putNull("balanceEod").put("paymentCount", 0).put("balanceMismatch", false);
        var plans = input.putArray("knownPlans");
        plans.add(plan(EXPENSE_PLAN, asOf.plusDays(5), "OUT", "1600.00", "Зарплата"));
        plans.add(plan(INCOME_PLAN, asOf.plusDays(20), "IN", "200.00", "Оплата клиента"));
        plans.add(plan(OVERDUE_PLAN, asOf.minusDays(2), "IN", "400.00", "Просроченная оплата"));
        for (JsonNode plan : plans) {
            ((ObjectNode) plan).put("companyId", account.company().toString()).put("accountId", account.account().toString());
        }
        String fingerprint = Fingerprint.of(input);
        input.put("fingerprint", fingerprint);
        jdbc.sql("insert into snapshots(id,company_id,account_id,kind,as_of,input_version,fingerprint,payload,provenance_json,created_at)"
                        + " values(:s,:c,:a,'FORECAST',:asOf,:v,:fp,cast(:body as jsonb),'{}',:created)")
                .param("s", snapshot).param("c", account.company()).param("a", account.account()).param("asOf", asOf)
                .param("v", version).param("fp", fingerprint).param("body", input.toString())
                .param("created", Timestamp.from(created)).update();
        jdbc.sql("insert into jobs(id,company_id,account_id,type,status,snapshot_id,result_id,lease_owner,lease_until,"
                        + "attempts,max_attempts,not_before,created_by,created_at) values(:j,:c,:a,'FORECAST','RUNNING',:s,:r,"
                        + "'alerts-test-worker',:until,1,3,:created,:u,:created)")
                .param("j", job).param("c", account.company()).param("a", account.account()).param("s", snapshot)
                .param("r", result).param("until", Timestamp.from(NOW.plusSeconds(120)))
                .param("created", Timestamp.from(created)).param("u", account.owner()).update();
        return new Calculation(job, result, input);
    }

    private ObjectNode plan(UUID id, LocalDate date, String direction, String amount, String title) {
        return json.createObjectNode().put("id", id.toString()).put("title", title).put("dueDate", date.toString())
                .put("direction", direction).put("amount", amount).put("remainingAmount", amount)
                .put("status", "OPEN").put("categoryCode", "OUT".equals(direction) ? "SALARY" : "REVENUE_CUSTOMERS")
                .put("source", "MANUAL").put("relation", "ADDITIONAL").putNull("counterpartyKey").putNull("note")
                .putNull("recurringPatternId").putNull("baselineCounterpartyKey").putNull("sourceDocument")
                .put("settledAmount", "0.00").put("overdue", !date.isAfter(AS_OF)).put("knownAt", NOW.minusSeconds(60).toString())
                .put("createdAt", NOW.minusSeconds(60).toString()).put("updatedAt", NOW.minusSeconds(60).toString()).put("version", 0);
    }

    private Void saveAndReturn(Calculation calculation) {
        save(calculation, true);
        return null;
    }

    private void save(Calculation calculation, boolean gaps) {
        var response = ForecastResponses.valid(calculation.input(), calculation.result(), calculation.job(), "100.00", "80.00");
        if (gaps) {
            BigDecimal minimum = new BigDecimal("-500.00");
            LocalDate asOf = LocalDate.parse(calculation.input().path("asOfDate").asText());
            for (int i = 1; i <= 30; i++) {
                ObjectNode day = (ObjectNode) response.path("days").get(i - 1);
                BigDecimal expense = i == 5 ? new BigDecimal("1600.00") : BigDecimal.ZERO;
                BigDecimal income = i == 20 ? new BigDecimal("200.00") : BigDecimal.ZERO;
                BigDecimal balance = new BigDecimal("1000.00").add(new BigDecimal("20.00").multiply(BigDecimal.valueOf(i)))
                        .subtract(i >= 5 ? new BigDecimal("1600.00") : BigDecimal.ZERO)
                        .add(i >= 20 ? new BigDecimal("200.00") : BigDecimal.ZERO);
                day.put("plannedOut", expense.setScale(2).toPlainString()).put("plannedIn", income.setScale(2).toPlainString())
                        .put("inflow", new BigDecimal("100.00").add(income).setScale(2).toPlainString())
                        .put("outflow", new BigDecimal("80.00").add(expense).setScale(2).toPlainString())
                        .put("balance", balance.setScale(2).toPlainString());
            }
            response.put("expectedMinimumBalance", minimum.toPlainString()).put("maxExpectedShortfall", "500.00")
                    .put("firstExpectedGapDate", asOf.plusDays(5).toString());
            response.withArray("gaps").add(gap(CASH_GAP, "CASH_GAP", asOf.plusDays(5), asOf.plusDays(19), "0.00", "500.00"));
            response.withArray("gaps").add(gap(BUFFER_GAP, "BUFFER_BREACH", asOf.plusDays(5), asOf.plusDays(24), "100.00", "600.00"));
        }
        response.withArray("warnings").addObject().put("code", "SHORT_HISTORY").put("message", "Short history").putNull("date");
        var job = jobs.findById(calculation.job()).orElseThrow();
        var snapshot = snapshots.findForWorker(job.companyId(), job.snapshotId()).orElseThrow();
        handler.accept(job, 1, "alerts-test-worker", snapshot, response.toString());
    }

    private ObjectNode gap(UUID id, String kind, LocalDate from, LocalDate to, String threshold, String shortfall) {
        return json.createObjectNode().put("id", id.toString()).put("kind", kind).put("fromDate", from.toString())
                .put("toDate", to.toString()).put("threshold", threshold).put("expectedMinimumBalance", "-500.00")
                .put("expectedShortfall", shortfall).putNull("probability").putNull("shortfallP50").putNull("shortfallP90")
                .put("shortfallBasis", "CONDITIONAL_ON_BREACH").put("riskEstimateAvailability", "INSUFFICIENT_HISTORY");
    }

    private JsonNode list(AccountContext account) throws Exception {
        var response = send("GET", base(account) + "/alerts", account.owner(), null);
        assertThat(response.status()).as(response.raw()).isEqualTo(200);
        return response.body().path("items");
    }

    private Response details(AccountContext account, UUID forecast, UUID gap) throws Exception {
        return send("GET", base(account) + "/forecasts/" + forecast + "/gaps/" + gap, account.viewer(), null);
    }

    private static JsonNode alert(JsonNode alerts, String code) {
        for (JsonNode a : alerts) {
            if (code.equals(a.path("code").asText())) {
                return a;
            }
        }
        throw new AssertionError("Missing alert " + code);
    }

    private static String base(AccountContext account) {
        return "/api/v1/orchestrator/companies/" + account.company() + "/accounts/" + account.account();
    }

    private record AccountContext(UUID owner, UUID viewer, UUID accountant, UUID company, UUID account) {}
    private record Calculation(UUID job, UUID result, ObjectNode input) {}
}
