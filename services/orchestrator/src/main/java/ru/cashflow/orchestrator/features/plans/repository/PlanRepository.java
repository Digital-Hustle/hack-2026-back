package ru.cashflow.orchestrator.features.plans.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.Allocation;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.ExpectedPayment;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanSource;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.PlanStatus;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.Relation;
import ru.cashflow.orchestrator.features.plans.model.PlanTypes.SourceDocument;

@Repository
public class PlanRepository {

    private static final String COLUMNS = "id, company_id, account_id, title, direction, amount, due_date, "
            + "category_code, counterparty_key, note, source, external_system, external_id, document_number, "
            + "issued_date, relation, recurring_pattern_id, baseline_counterparty_key, status, settled_amount, "
            + "known_at, created_at, updated_at, version";
    private static final String ALLOCATION_COLUMNS = "id, expected_payment_id, payment_id, amount, created_at, "
            + "actor_id, reversed_at, reversal_actor_id";

    private final JdbcClient jdbc;

    public PlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ExpectedPayment p) {
        SourceDocument d = p.sourceDocument();
        jdbc.sql("insert into expected_payments (" + COLUMNS + ") values (:id, :company, :account, :title, :direction, "
                        + ":amount, :due, :category, :key, :note, :source, :system, :external, :number, :issued, "
                        + ":relation, :pattern, :baseline, :status, :settled, :known, :created, :updated, :version)")
                .params(params(p, d)).update();
    }

    public boolean update(ExpectedPayment p, long expectedVersion) {
        SourceDocument d = p.sourceDocument();
        var params = params(p, d);
        params.put("expected", expectedVersion);
        return jdbc.sql("update expected_payments set title = :title, direction = :direction, amount = :amount, "
                        + "due_date = :due, category_code = :category, counterparty_key = :key, note = :note, "
                        + "source = :source, external_system = :system, external_id = :external, "
                        + "document_number = :number, issued_date = :issued, relation = :relation, "
                        + "recurring_pattern_id = :pattern, baseline_counterparty_key = :baseline, status = :status, "
                        + "settled_amount = :settled, updated_at = :updated, version = :version "
                        + "where id = :id and company_id = :company and account_id = :account and version = :expected")
                .params(params).update() == 1;
    }

    public void insertRevision(ExpectedPayment p, String payload, UUID actorId) {
        jdbc.sql("insert into expected_payment_revisions (expected_payment_id, company_id, valid_at, version, payload, "
                        + "actor_id) values (:id, :company, :valid, :version, cast(:payload as jsonb), :actor)")
                .param("id", p.id()).param("company", p.companyId()).param("valid", Timestamp.from(p.updatedAt()))
                .param("version", p.version()).param("payload", payload).param("actor", actorId).update();
    }

    public Optional<ExpectedPayment> find(CompanyScope scope, UUID accountId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from expected_payments where id = :id and company_id = :company "
                        + "and account_id = :account")
                .param("id", id).param("company", scope.companyId()).param("account", accountId)
                .query(PlanRepository::plan).optional();
    }

    public Optional<ExpectedPayment> lock(CompanyScope scope, UUID accountId, UUID id) {
        return jdbc.sql("select " + COLUMNS + " from expected_payments where id = :id and company_id = :company "
                        + "and account_id = :account for update")
                .param("id", id).param("company", scope.companyId()).param("account", accountId)
                .query(PlanRepository::plan).optional();
    }

    public List<ExpectedPayment> list(CompanyScope scope, UUID accountId, LocalDate from, LocalDate to) {
        return jdbc.sql("select " + COLUMNS + " from expected_payments where company_id = :company "
                        + "and account_id = :account and due_date between :from and :to order by due_date, id")
                .param("company", scope.companyId()).param("account", accountId).param("from", from).param("to", to)
                .query(PlanRepository::plan).list();
    }

    public List<ExpectedPayment> overdueOutflowsBefore(CompanyScope scope, UUID accountId, LocalDate before) {
        return jdbc.sql("select " + COLUMNS + " from expected_payments where company_id = :company "
                        + "and account_id = :account and direction = 'OUT' and due_date < :before "
                        + "and status in ('OPEN', 'PARTIALLY_SETTLED') and amount > settled_amount order by due_date, id")
                .param("company", scope.companyId()).param("account", accountId).param("before", before)
                .query(PlanRepository::plan).list();
    }

    /** Full plan state per revision known strictly before the cutoff (handoff §3 historical replay). */
    public List<String> revisionPayloadsBefore(CompanyScope scope, UUID accountId, Instant knownBefore) {
        return jdbc.sql("select distinct on (r.expected_payment_id) r.payload::text as payload "
                        + "from expected_payment_revisions r join expected_payments p on p.id = r.expected_payment_id "
                        + "where r.company_id = :company and p.account_id = :account and r.valid_at < :known "
                        + "order by r.expected_payment_id, r.valid_at desc, r.version desc")
                .param("company", scope.companyId()).param("account", accountId)
                .param("known", Timestamp.from(knownBefore)).query(String.class).list();
    }

    public void insertAllocation(CompanyScope scope, UUID accountId, Allocation a) {
        jdbc.sql("insert into allocations (" + ALLOCATION_COLUMNS + ", company_id, account_id) values (:id, :plan, "
                        + ":payment, :amount, :created, :actor, null, null, :company, :account)")
                .param("id", a.id()).param("plan", a.expectedPaymentId()).param("payment", a.paymentId())
                .param("amount", a.amount()).param("created", Timestamp.from(a.createdAt())).param("actor", a.actorId())
                .param("company", scope.companyId()).param("account", accountId).update();
    }

    public Optional<Allocation> lockAllocation(CompanyScope scope, UUID accountId, UUID allocationId) {
        return jdbc.sql("select " + ALLOCATION_COLUMNS + " from allocations where id = :id and company_id = :company "
                        + "and account_id = :account for update")
                .param("id", allocationId).param("company", scope.companyId()).param("account", accountId)
                .query(PlanRepository::allocation).optional();
    }

    public void reverseAllocation(UUID allocationId, Instant now, UUID actorId) {
        jdbc.sql("update allocations set reversed_at = :now, reversal_actor_id = :actor where id = :id "
                        + "and reversed_at is null")
                .param("now", Timestamp.from(now)).param("actor", actorId).param("id", allocationId).update();
    }

    public BigDecimal activeAllocatedToPayment(CompanyScope scope, UUID paymentId) {
        return jdbc.sql("select coalesce(sum(amount), 0) from allocations where company_id = :company "
                        + "and payment_id = :payment and reversed_at is null")
                .param("company", scope.companyId()).param("payment", paymentId).query(BigDecimal.class).single();
    }

    public BigDecimal activeAllocatedToPlan(UUID planId) {
        return jdbc.sql("select coalesce(sum(amount), 0) from allocations where expected_payment_id = :plan "
                        + "and reversed_at is null")
                .param("plan", planId).query(BigDecimal.class).single();
    }

    public List<Allocation> allocations(CompanyScope scope, UUID planId) {
        return jdbc.sql("select " + ALLOCATION_COLUMNS + " from allocations where company_id = :company "
                        + "and expected_payment_id = :plan order by created_at, id")
                .param("company", scope.companyId()).param("plan", planId).query(PlanRepository::allocation).list();
    }

    public List<Allocation> allocationsForAccount(CompanyScope scope, UUID accountId) {
        return jdbc.sql("select " + ALLOCATION_COLUMNS + " from allocations where company_id = :company "
                        + "and account_id = :account order by created_at, id")
                .param("company", scope.companyId()).param("account", accountId)
                .query(PlanRepository::allocation).list();
    }

    private static java.util.Map<String, Object> params(ExpectedPayment p, SourceDocument d) {
        var m = new java.util.HashMap<String, Object>();
        m.put("id", p.id());
        m.put("company", p.companyId());
        m.put("account", p.accountId());
        m.put("title", p.title());
        m.put("direction", p.direction().name());
        m.put("amount", p.amount());
        m.put("due", p.dueDate());
        m.put("category", p.categoryCode());
        m.put("key", p.counterpartyKey());
        m.put("note", p.note());
        m.put("source", p.source().name());
        m.put("system", d == null ? null : d.externalSystem());
        m.put("external", d == null ? null : d.externalId());
        m.put("number", d == null ? null : d.documentNumber());
        m.put("issued", d == null ? null : d.issuedDate());
        m.put("relation", p.relation().name());
        m.put("pattern", p.recurringPatternId());
        m.put("baseline", p.baselineCounterpartyKey());
        m.put("status", p.status().name());
        m.put("settled", p.settledAmount());
        m.put("known", Timestamp.from(p.knownAt()));
        m.put("created", Timestamp.from(p.createdAt()));
        m.put("updated", Timestamp.from(p.updatedAt()));
        m.put("version", p.version());
        return m;
    }

    private static ExpectedPayment plan(ResultSet rs, int row) throws SQLException {
        String system = rs.getString("external_system");
        SourceDocument document = system == null ? null : new SourceDocument(system, rs.getString("external_id"),
                rs.getString("document_number"), rs.getObject("issued_date", LocalDate.class));
        return new ExpectedPayment(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("account_id", UUID.class), rs.getString("title"),
                Direction.valueOf(rs.getString("direction")), rs.getBigDecimal("amount"),
                rs.getObject("due_date", LocalDate.class), rs.getString("category_code"),
                rs.getString("counterparty_key"), rs.getString("note"), Relation.valueOf(rs.getString("relation")),
                rs.getObject("recurring_pattern_id", UUID.class), rs.getString("baseline_counterparty_key"),
                PlanSource.valueOf(rs.getString("source")), document, PlanStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("settled_amount"), rs.getTimestamp("known_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }

    private static Allocation allocation(ResultSet rs, int row) throws SQLException {
        Timestamp reversed = rs.getTimestamp("reversed_at");
        return new Allocation(rs.getObject("id", UUID.class), rs.getObject("expected_payment_id", UUID.class),
                rs.getObject("payment_id", UUID.class), rs.getBigDecimal("amount"),
                rs.getTimestamp("created_at").toInstant(), rs.getObject("actor_id", UUID.class),
                reversed == null ? null : reversed.toInstant(), rs.getObject("reversal_actor_id", UUID.class));
    }
}
