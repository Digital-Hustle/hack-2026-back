package ru.cashflow.orchestrator.features.ledger.repository;

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
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.AnchorSource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.BalanceAnchor;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategoryRevision;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CategorySource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.CompletenessRange;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.DailyObservation;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.DayStatus;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Payment;

/** Every query is bound to CompanyScope and an account of that company; there is no unscoped read. */
@Repository
public class LedgerRepository {

    private static final String PAYMENT_COLUMNS = "id, company_id, account_id, import_id, external_operation_id, "
            + "source_row, operation_date, amount, direction, counterparty_key, counterparty_inn, counterparty_name, "
            + "purpose, category_code, category_source, rule_version, balance_eod, version, known_at, created_at";

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPayment(Payment p) {
        jdbc.sql("insert into payments (" + PAYMENT_COLUMNS + ") values (:id, :company, :account, :import, :external, "
                        + ":row, :date, :amount, :direction, :key, :inn, :name, :purpose, :category, :source, :rule, "
                        + ":eod, 0, :known, :created)")
                .param("id", p.id()).param("company", p.companyId()).param("account", p.accountId())
                .param("import", p.importId()).param("external", p.externalOperationId()).param("row", p.sourceRow())
                .param("date", p.operationDate()).param("amount", p.amount()).param("direction", p.direction().name())
                .param("key", p.counterpartyKey()).param("inn", p.counterpartyInn()).param("name", p.counterpartyName())
                .param("purpose", p.purpose()).param("category", p.categoryCode())
                .param("source", p.categorySource().name()).param("rule", p.ruleVersion()).param("eod", p.balanceEod())
                .param("known", Timestamp.from(p.knownAt())).param("created", Timestamp.from(p.createdAt()))
                .update();
        insertRevision(p.companyId(), new CategoryRevision(p.id(), p.categoryCode(), p.categorySource(),
                p.ruleVersion(), p.knownAt(), null, 0));
    }

    /**
     * Inserts the fact unless the same (company, account, external_operation_id) already exists.
     * Returns false for an already-present fact: a repeated finalize or re-import never creates it twice.
     */
    public boolean insertPaymentIfAbsent(Payment p) {
        int rows = jdbc.sql("insert into payments (" + PAYMENT_COLUMNS + ") values (:id, :company, :account, :import, "
                        + ":external, :row, :date, :amount, :direction, :key, :inn, :name, :purpose, :category, :source, "
                        + ":rule, :eod, 0, :known, :created) "
                        + "on conflict (company_id, account_id, external_operation_id) "
                        + "where external_operation_id is not null do nothing")
                .param("id", p.id()).param("company", p.companyId()).param("account", p.accountId())
                .param("import", p.importId()).param("external", p.externalOperationId()).param("row", p.sourceRow())
                .param("date", p.operationDate()).param("amount", p.amount()).param("direction", p.direction().name())
                .param("key", p.counterpartyKey()).param("inn", p.counterpartyInn()).param("name", p.counterpartyName())
                .param("purpose", p.purpose()).param("category", p.categoryCode())
                .param("source", p.categorySource().name()).param("rule", p.ruleVersion()).param("eod", p.balanceEod())
                .param("known", Timestamp.from(p.knownAt())).param("created", Timestamp.from(p.createdAt()))
                .update();
        if (rows == 1) {
            insertRevision(p.companyId(), new CategoryRevision(p.id(), p.categoryCode(), p.categorySource(),
                    p.ruleVersion(), p.knownAt(), null, 0));
        }
        return rows == 1;
    }

    public void insertRevision(UUID companyId, CategoryRevision r) {
        jdbc.sql("insert into payment_revisions (id, payment_id, company_id, category_code, category_source, "
                        + "rule_version, known_at, actor_id, version) values (:id, :payment, :company, :category, "
                        + ":source, :rule, :known, :actor, :version)")
                .param("id", UUID.randomUUID()).param("payment", r.paymentId()).param("company", companyId)
                .param("category", r.categoryCode()).param("source", r.categorySource().name())
                .param("rule", r.ruleVersion()).param("known", Timestamp.from(r.knownAt()))
                .param("actor", r.actorId()).param("version", r.version()).update();
    }

    public Optional<Payment> lockPayment(CompanyScope scope, UUID accountId, UUID paymentId) {
        return jdbc.sql("select " + PAYMENT_COLUMNS + " from payments where id = :id and company_id = :company "
                        + "and account_id = :account for update")
                .param("id", paymentId).param("company", scope.companyId()).param("account", accountId)
                .query(LedgerRepository::payment).optional();
    }

    public Optional<Payment> findPayment(CompanyScope scope, UUID accountId, UUID paymentId) {
        return jdbc.sql("select " + PAYMENT_COLUMNS + " from payments where id = :id and company_id = :company "
                        + "and account_id = :account")
                .param("id", paymentId).param("company", scope.companyId()).param("account", accountId)
                .query(LedgerRepository::payment).optional();
    }

    public boolean updateCategory(CompanyScope scope, UUID paymentId, long expectedVersion, String category,
                                  CategorySource source, String ruleVersion) {
        return jdbc.sql("update payments set category_code = :category, category_source = :source, "
                        + "rule_version = :rule, version = version + 1 "
                        + "where id = :id and company_id = :company and version = :version")
                .param("category", category).param("source", source.name()).param("rule", ruleVersion)
                .param("id", paymentId).param("company", scope.companyId()).param("version", expectedVersion)
                .update() == 1;
    }

    public List<Payment> payments(CompanyScope scope, UUID accountId, LocalDate from, LocalDate to, int limit,
                                  int offset) {
        return jdbc.sql("select " + PAYMENT_COLUMNS + " from payments where company_id = :company "
                        + "and account_id = :account and operation_date between :from and :to "
                        + "order by operation_date, id limit :limit offset :offset")
                .param("company", scope.companyId()).param("account", accountId).param("from", from)
                .param("to", to).param("limit", limit).param("offset", offset)
                .query(LedgerRepository::payment).list();
    }

    /** Facts known strictly before the cutoff; the date bound excludes future-dated rows. */
    public List<Payment> paymentsKnownBefore(CompanyScope scope, UUID accountId, LocalDate from, LocalDate to,
                                             Instant knownBefore) {
        return jdbc.sql("select " + PAYMENT_COLUMNS + " from payments where company_id = :company "
                        + "and account_id = :account and operation_date between :from and :to and known_at < :known "
                        + "order by operation_date, id")
                .param("company", scope.companyId()).param("account", accountId).param("from", from)
                .param("to", to).param("known", Timestamp.from(knownBefore))
                .query(LedgerRepository::payment).list();
    }

    /** Category state at the cutoff: the newest revision known strictly before it. */
    public List<CategoryRevision> revisionsKnownBefore(CompanyScope scope, UUID accountId, LocalDate from,
                                                       LocalDate to, Instant knownBefore) {
        return jdbc.sql("select distinct on (r.payment_id) r.payment_id, r.category_code, r.category_source, "
                        + "r.rule_version, r.known_at, r.actor_id, r.version from payment_revisions r "
                        + "join payments p on p.id = r.payment_id and p.company_id = r.company_id "
                        + "where r.company_id = :company and p.account_id = :account "
                        + "and p.operation_date between :from and :to and r.known_at < :known "
                        + "order by r.payment_id, r.known_at desc, r.version desc")
                .param("company", scope.companyId()).param("account", accountId).param("from", from)
                .param("to", to).param("known", Timestamp.from(knownBefore))
                .query((rs, row) -> new CategoryRevision(rs.getObject("payment_id", UUID.class),
                        rs.getString("category_code"), CategorySource.valueOf(rs.getString("category_source")),
                        rs.getString("rule_version"), rs.getTimestamp("known_at").toInstant(),
                        rs.getObject("actor_id", UUID.class), rs.getLong("version")))
                .list();
    }

    public List<CategoryRevision> revisions(CompanyScope scope, UUID paymentId) {
        return jdbc.sql("select payment_id, category_code, category_source, rule_version, known_at, actor_id, version "
                        + "from payment_revisions where company_id = :company and payment_id = :payment "
                        + "order by version")
                .param("company", scope.companyId()).param("payment", paymentId)
                .query((rs, row) -> new CategoryRevision(rs.getObject("payment_id", UUID.class),
                        rs.getString("category_code"), CategorySource.valueOf(rs.getString("category_source")),
                        rs.getString("rule_version"), rs.getTimestamp("known_at").toInstant(),
                        rs.getObject("actor_id", UUID.class), rs.getLong("version")))
                .list();
    }

    public void insertCompleteness(CompanyScope scope, CompletenessRange r) {
        jdbc.sql("insert into completeness_ranges (id, company_id, account_id, from_date, to_date, completeness, "
                        + "reason, known_at, actor_id) values (:id, :company, :account, :from, :to, :state, :reason, "
                        + ":known, :actor)")
                .param("id", r.id()).param("company", scope.companyId()).param("account", r.accountId())
                .param("from", r.fromDate()).param("to", r.toDate()).param("state", r.completeness().name())
                .param("reason", r.reason()).param("known", Timestamp.from(r.knownAt())).param("actor", r.actorId())
                .update();
    }

    public List<CompletenessRange> completenessKnownBefore(CompanyScope scope, UUID accountId, Instant knownBefore) {
        return jdbc.sql("select id, account_id, from_date, to_date, completeness, reason, known_at, actor_id "
                        + "from completeness_ranges where company_id = :company and account_id = :account "
                        + "and known_at < :known order by known_at, id")
                .param("company", scope.companyId()).param("account", accountId)
                .param("known", Timestamp.from(knownBefore))
                .query((rs, row) -> new CompletenessRange(rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class), rs.getObject("from_date", LocalDate.class),
                        rs.getObject("to_date", LocalDate.class), Completeness.valueOf(rs.getString("completeness")),
                        rs.getString("reason"), rs.getTimestamp("known_at").toInstant(),
                        rs.getObject("actor_id", UUID.class)))
                .list();
    }

    public void insertAnchor(CompanyScope scope, BalanceAnchor a, UUID importId) {
        jdbc.sql("insert into balance_anchors (id, company_id, account_id, date, balance, source, reason, known_at, "
                        + "actor_id, supersedes_id, import_id) values (:id, :company, :account, :date, :balance, "
                        + ":source, :reason, :known, :actor, :supersedes, :import)")
                .param("id", a.id()).param("company", scope.companyId()).param("account", a.accountId())
                .param("date", a.date()).param("balance", a.balance()).param("source", a.source().name())
                .param("reason", a.reason()).param("known", Timestamp.from(a.knownAt())).param("actor", a.actorId())
                .param("supersedes", a.supersedesId()).param("import", importId).update();
    }

    public Optional<BalanceAnchor> findAnchor(CompanyScope scope, UUID accountId, UUID anchorId) {
        return jdbc.sql("select id, account_id, date, balance, source, reason, known_at, actor_id, supersedes_id "
                        + "from balance_anchors where id = :id and company_id = :company and account_id = :account")
                .param("id", anchorId).param("company", scope.companyId()).param("account", accountId)
                .query(LedgerRepository::anchor).optional();
    }

    public List<BalanceAnchor> anchorsKnownBefore(CompanyScope scope, UUID accountId, Instant knownBefore) {
        return jdbc.sql("select id, account_id, date, balance, source, reason, known_at, actor_id, supersedes_id "
                        + "from balance_anchors where company_id = :company and account_id = :account "
                        + "and known_at < :known order by date, known_at, id")
                .param("company", scope.companyId()).param("account", accountId)
                .param("known", Timestamp.from(knownBefore))
                .query(LedgerRepository::anchor).list();
    }

    public Optional<LocalDate[]> paymentDateRange(CompanyScope scope, UUID accountId) {
        return jdbc.sql("select min(operation_date) as first_date, max(operation_date) as last_date from payments "
                        + "where company_id = :company and account_id = :account")
                .param("company", scope.companyId()).param("account", accountId)
                .query((rs, row) -> rs.getObject("first_date", LocalDate.class) == null ? null
                        : new LocalDate[] {rs.getObject("first_date", LocalDate.class),
                                rs.getObject("last_date", LocalDate.class)})
                .optional();
    }

    public void replaceObservations(CompanyScope scope, UUID accountId, List<DailyObservation> days, long version) {
        jdbc.sql("delete from daily_observations where company_id = :company and account_id = :account")
                .param("company", scope.companyId()).param("account", accountId).update();
        for (DailyObservation d : days) {
            jdbc.sql("insert into daily_observations (company_id, account_id, date, inflow, outflow, eod, status, "
                            + "payment_count, balance_mismatch, input_version) values (:company, :account, :date, "
                            + ":inflow, :outflow, :eod, :status, :count, :mismatch, :version)")
                    .param("company", scope.companyId()).param("account", accountId).param("date", d.date())
                    .param("inflow", d.inflow()).param("outflow", d.outflow()).param("eod", d.balanceEod())
                    .param("status", d.status().name()).param("count", d.paymentCount())
                    .param("mismatch", d.balanceMismatch()).param("version", version).update();
        }
    }

    public List<DailyObservation> observations(CompanyScope scope, UUID accountId, LocalDate from, LocalDate to) {
        return jdbc.sql("select date, inflow, outflow, eod, status, payment_count, balance_mismatch "
                        + "from daily_observations where company_id = :company and account_id = :account "
                        + "and date between :from and :to order by date")
                .param("company", scope.companyId()).param("account", accountId).param("from", from)
                .param("to", to)
                .query((rs, row) -> new DailyObservation(rs.getObject("date", LocalDate.class),
                        rs.getBigDecimal("inflow"), rs.getBigDecimal("outflow"), rs.getBigDecimal("eod"),
                        DayStatus.valueOf(rs.getString("status")), rs.getInt("payment_count"),
                        rs.getBoolean("balance_mismatch")))
                .list();
    }

    private static Payment payment(ResultSet rs, int row) throws SQLException {
        int sourceRow = rs.getInt("source_row");
        return new Payment(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("account_id", UUID.class), rs.getObject("import_id", UUID.class),
                rs.getString("external_operation_id"), rs.wasNull() ? null : sourceRow,
                rs.getObject("operation_date", LocalDate.class), rs.getBigDecimal("amount"),
                Direction.valueOf(rs.getString("direction")), rs.getString("counterparty_key"),
                rs.getString("counterparty_inn"), rs.getString("counterparty_name"), rs.getString("purpose"),
                rs.getString("category_code"), CategorySource.valueOf(rs.getString("category_source")),
                rs.getString("rule_version"), rs.getBigDecimal("balance_eod"), rs.getLong("version"),
                rs.getTimestamp("known_at").toInstant(), rs.getTimestamp("created_at").toInstant());
    }

    private static BalanceAnchor anchor(ResultSet rs, int row) throws SQLException {
        return new BalanceAnchor(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getObject("date", LocalDate.class), rs.getBigDecimal("balance"),
                AnchorSource.valueOf(rs.getString("source")), rs.getString("reason"),
                rs.getTimestamp("known_at").toInstant(), rs.getObject("actor_id", UUID.class),
                rs.getObject("supersedes_id", UUID.class));
    }
}
