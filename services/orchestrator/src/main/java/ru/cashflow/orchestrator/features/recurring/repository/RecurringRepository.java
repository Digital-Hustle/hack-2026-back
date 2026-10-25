package ru.cashflow.orchestrator.features.recurring.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.BusinessDayRule;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Frequency;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.PatternStatus;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.RecurringPattern;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.Schedule;
import ru.cashflow.orchestrator.features.recurring.model.RecurringTypes.StatusSource;

@Repository
public class RecurringRepository {

    private static final String COLUMNS = "id, company_id, account_id, stable_key, direction, category_code, "
            + "counterparty_key, status, status_source, frequency, weekday, day_of_month, business_day_rule, amount, "
            + "observations, first_seen, last_seen, detector_version, input_version, known_at, updated_at, version, "
            + "employee_payment";

    private final JdbcClient jdbc;

    public RecurringRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RecurringPattern> lockByKey(CompanyScope scope, UUID accountId, String stableKey) {
        return jdbc.sql("select " + COLUMNS + " from recurring_patterns where company_id = :company "
                        + "and account_id = :account and stable_key = :key for update")
                .param("company", scope.companyId()).param("account", accountId).param("key", stableKey)
                .query(RecurringRepository::pattern).optional();
    }

    public Optional<RecurringPattern> lock(CompanyScope scope, UUID accountId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from recurring_patterns where id = :id and company_id = :company "
                        + "and account_id = :account for update")
                .param("id", id).param("company", scope.companyId()).param("account", accountId)
                .query(RecurringRepository::pattern).optional();
    }

    public Optional<RecurringPattern> find(CompanyScope scope, UUID accountId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from recurring_patterns where id = :id and company_id = :company "
                        + "and account_id = :account")
                .param("id", id).param("company", scope.companyId()).param("account", accountId)
                .query(RecurringRepository::pattern).optional();
    }

    public Optional<RecurringPattern> findById(CompanyScope scope, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from recurring_patterns where company_id = :company and id = :id")
                .param("company", scope.companyId()).param("id", id).query(RecurringRepository::pattern).optional();
    }

    /** Batch lookup for employee list responses, strictly scoped to the company. */
    public List<RecurringPattern> findByIds(CompanyScope scope, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("select " + COLUMNS + " from recurring_patterns where company_id = :company "
                        + "and id in (:ids)")
                .param("company", scope.companyId()).param("ids", ids)
                .query(RecurringRepository::pattern).list();
    }

    public List<RecurringPattern> list(CompanyScope scope, UUID accountId) {
        return jdbc.sql("select " + COLUMNS + " from recurring_patterns where company_id = :company "
                        + "and account_id = :account order by stable_key")
                .param("company", scope.companyId()).param("account", accountId)
                .query(RecurringRepository::pattern).list();
    }

    public void insert(RecurringPattern p) {
        jdbc.sql("insert into recurring_patterns (" + COLUMNS + ") values (:id, :company, :account, :key, :direction, "
                        + ":category, :cp, :status, :source, :frequency, :weekday, :dom, :rule, :amount, :obs, :first, "
                        + ":last, :detector, :input, :known, :updated, :version, :employee)")
                .params(params(p)).update();
    }

    public void update(RecurringPattern p, long expectedVersion) {
        var params = params(p);
        params.put("expected", expectedVersion);
        int rows = jdbc.sql("update recurring_patterns set status = :status, status_source = :source, "
                        + "frequency = :frequency, weekday = :weekday, day_of_month = :dom, business_day_rule = :rule, "
                        + "amount = :amount, observations = :obs, first_seen = :first, last_seen = :last, "
                        + "detector_version = :detector, input_version = :input, updated_at = :updated, "
                        + "version = :version, employee_payment = :employee where id = :id and company_id = :company "
                        + "and version = :expected")
                .params(params).update();
        if (rows != 1) {
            throw new IllegalStateException("recurring pattern changed concurrently");
        }
    }

    public void insertRevision(RecurringPattern p, String payload, UUID actorId) {
        jdbc.sql("insert into recurring_revisions (pattern_id, company_id, known_at, payload, actor_id, version) "
                        + "values (:id, :company, :known, cast(:payload as jsonb), :actor, :version)")
                .param("id", p.id()).param("company", p.companyId()).param("known", Timestamp.from(p.updatedAt()))
                .param("payload", payload).param("actor", actorId).param("version", p.version()).update();
    }

    /** Full pattern state per pattern known strictly before the cutoff. */
    public List<String> revisionPayloadsBefore(CompanyScope scope, UUID accountId, Instant knownBefore) {
        return jdbc.sql("select distinct on (r.pattern_id) r.payload::text from recurring_revisions r "
                        + "join recurring_patterns p on p.id = r.pattern_id where r.company_id = :company "
                        + "and p.account_id = :account and r.known_at < :known "
                        + "order by r.pattern_id, r.known_at desc, r.version desc")
                .param("company", scope.companyId()).param("account", accountId)
                .param("known", Timestamp.from(knownBefore)).query(String.class).list();
    }

    private static Map<String, Object> params(RecurringPattern p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.id());
        m.put("company", p.companyId());
        m.put("account", p.accountId());
        m.put("key", p.stableKey());
        m.put("direction", p.direction().name());
        m.put("category", p.categoryCode());
        m.put("cp", p.counterpartyKey());
        m.put("status", p.status().name());
        m.put("source", p.statusSource().name());
        m.put("frequency", p.schedule().frequency().name());
        m.put("weekday", p.schedule().weekday());
        m.put("dom", p.schedule().dayOfMonth());
        m.put("rule", p.schedule().businessDayRule().name());
        m.put("amount", p.amount());
        m.put("obs", p.observations());
        m.put("first", p.firstSeen());
        m.put("last", p.lastSeen());
        m.put("detector", p.detectorVersion());
        m.put("input", p.inputDataVersion());
        m.put("known", Timestamp.from(p.knownAt()));
        m.put("updated", Timestamp.from(p.updatedAt()));
        m.put("version", p.version());
        m.put("employee", p.employeePayment());
        return m;
    }

    private static RecurringPattern pattern(ResultSet rs, int row) throws SQLException {
        int weekday = rs.getInt("weekday");
        Integer weekdayValue = rs.wasNull() ? null : weekday;
        int dom = rs.getInt("day_of_month");
        Integer domValue = rs.wasNull() ? null : dom;
        return new RecurringPattern(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("account_id", UUID.class), rs.getString("stable_key"),
                Direction.valueOf(rs.getString("direction")), rs.getString("category_code"),
                rs.getString("counterparty_key"), PatternStatus.valueOf(rs.getString("status")),
                StatusSource.valueOf(rs.getString("status_source")),
                new Schedule(Frequency.valueOf(rs.getString("frequency")), weekdayValue, domValue,
                        BusinessDayRule.valueOf(rs.getString("business_day_rule"))),
                rs.getBigDecimal("amount"), rs.getInt("observations"), rs.getObject("first_seen", LocalDate.class),
                rs.getObject("last_seen", LocalDate.class), rs.getString("detector_version"),
                rs.getLong("input_version"), rs.getTimestamp("known_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"), rs.getBoolean("employee_payment"));
    }
}
