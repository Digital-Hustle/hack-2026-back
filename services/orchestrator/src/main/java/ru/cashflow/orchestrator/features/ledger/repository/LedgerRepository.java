package ru.cashflow.orchestrator.features.ledger.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.ledger.model.Counterparty;
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
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.PaymentSource;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.PaymentView;
import ru.cashflow.orchestrator.features.ledger.model.PaymentFilter;
import ru.cashflow.orchestrator.features.paging.model.PageQuery;
import ru.cashflow.orchestrator.features.paging.model.PageResult;

/** Every query is bound to CompanyScope and an account of that company; there is no unscoped read. */
@Repository
public class LedgerRepository {

    private static final String PAYMENT_COLUMNS = "id, company_id, account_id, import_id, external_operation_id, "
            + "source_row, operation_date, amount, direction, counterparty_key, counterparty_inn, counterparty_name, "
            + "purpose, category_code, category_source, rule_version, balance_eod, version, known_at, created_at, "
            + "source, created_by, manual_note";
    private static final String PAYMENT_VIEW_COLUMNS = "p.id, p.company_id, p.account_id, p.import_id, "
            + "p.external_operation_id, p.source_row, p.operation_date, p.amount, p.direction, p.counterparty_key, "
            + "p.counterparty_inn, p.counterparty_name, p.purpose, p.category_code, p.category_source, "
            + "p.rule_version, p.balance_eod, p.version, p.known_at, p.created_at, p.source, p.created_by, "
            + "p.manual_note, "
            + "(select v.voided_at from payment_voids v where v.payment_id=p.id) as voided_at, "
            + "(select v.reason from payment_voids v where v.payment_id=p.id) as void_reason, "
            + "(select v.replacement_payment_id from payment_voids v where v.payment_id=p.id) as replacement_id, "
            + "coalesce((select d.status from daily_observations d where d.company_id=p.company_id "
            + "and d.account_id=p.account_id and d.date=p.operation_date), 'MISSING') as day_status, "
            + "coalesce((select sum(a.amount) from allocations a where a.company_id = p.company_id "
            + "and a.payment_id = p.id and a.reversed_at is null), 0) as allocated_amount";

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPayment(Payment p) {
        jdbc.sql("insert into payments (" + PAYMENT_COLUMNS + ") values (:id, :company, :account, :import, :external, "
                        + ":row, :date, :amount, :direction, :key, :inn, :name, :purpose, :category, :categorySource, "
                        + ":rule, :eod, 0, :known, :created, :paymentSource, :createdBy, :manualNote)")
                .param("id", p.id()).param("company", p.companyId()).param("account", p.accountId())
                .param("import", p.importId()).param("external", p.externalOperationId()).param("row", p.sourceRow())
                .param("date", p.operationDate()).param("amount", p.amount()).param("direction", p.direction().name())
                .param("key", p.counterpartyKey()).param("inn", p.counterpartyInn()).param("name", p.counterpartyName())
                .param("purpose", p.purpose()).param("category", p.categoryCode())
                .param("categorySource", p.categorySource().name()).param("rule", p.ruleVersion())
                .param("eod", p.balanceEod())
                .param("known", Timestamp.from(p.knownAt())).param("created", Timestamp.from(p.createdAt()))
                .param("paymentSource", p.source().name()).param("createdBy", p.createdBy())
                .param("manualNote", p.manualNote())
                .update();
        insertRevision(p.companyId(), new CategoryRevision(p.id(), p.categoryCode(), p.categorySource(),
                p.ruleVersion(), p.knownAt(), p.createdBy(), 0));
    }

    /**
     * Inserts the fact unless the same (company, account, external_operation_id) already exists.
     * Returns false for an already-present fact: a repeated finalize or re-import never creates it twice.
     */
    public boolean insertPaymentIfAbsent(Payment p) {
        int rows = jdbc.sql("insert into payments (" + PAYMENT_COLUMNS + ") values (:id, :company, :account, :import, "
                        + ":external, :row, :date, :amount, :direction, :key, :inn, :name, :purpose, :category, "
                        + ":categorySource, :rule, :eod, 0, :known, :created, :paymentSource, :createdBy, :manualNote) "
                        + "on conflict (company_id, account_id, external_operation_id) "
                        + "where external_operation_id is not null do nothing")
                .param("id", p.id()).param("company", p.companyId()).param("account", p.accountId())
                .param("import", p.importId()).param("external", p.externalOperationId()).param("row", p.sourceRow())
                .param("date", p.operationDate()).param("amount", p.amount()).param("direction", p.direction().name())
                .param("key", p.counterpartyKey()).param("inn", p.counterpartyInn()).param("name", p.counterpartyName())
                .param("purpose", p.purpose()).param("category", p.categoryCode())
                .param("categorySource", p.categorySource().name()).param("rule", p.ruleVersion())
                .param("eod", p.balanceEod())
                .param("known", Timestamp.from(p.knownAt())).param("created", Timestamp.from(p.createdAt()))
                .param("paymentSource", p.source().name()).param("createdBy", p.createdBy())
                .param("manualNote", p.manualNote())
                .update();
        if (rows == 1) {
            insertRevision(p.companyId(), new CategoryRevision(p.id(), p.categoryCode(), p.categorySource(),
                    p.ruleVersion(), p.knownAt(), p.createdBy(), 0));
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

    public PageResult<PaymentView> payments(CompanyScope scope, UUID accountId, PaymentFilter filter,
                                            PageQuery page) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("company", scope.companyId());
        params.put("account", accountId);
        String where = paymentWhere(filter, params);
        String order = filter.ascending() ? "asc" : "desc";
        long total = jdbc.sql("select count(*) from payments p where " + where)
                .params(params).query(Long.class).single();
        params.put("limit", page.size());
        params.put("offset", page.offset());
        List<PaymentView> items = jdbc.sql("select " + PAYMENT_VIEW_COLUMNS + " from payments p "
                        + "where " + where + " order by p.operation_date " + order + ", p.id " + order
                        + " limit :limit offset :offset")
                .params(params).query(LedgerRepository::paymentView).list();
        return PageResult.of(items, total);
    }

    private static String paymentWhere(PaymentFilter filter, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder("p.company_id = :company and p.account_id = :account "
                + "and not exists (select 1 from payment_voids v where v.payment_id=p.id)");
        if (filter.fromDate() != null) {
            sql.append(" and p.operation_date >= :from");
            params.put("from", filter.fromDate());
        }
        if (filter.toDate() != null) {
            sql.append(" and p.operation_date <= :to");
            params.put("to", filter.toDate());
        }
        if (filter.direction() != null) {
            sql.append(" and p.direction = :direction");
            params.put("direction", filter.direction().name());
        }
        if (filter.categoryCode() != null) {
            sql.append(" and p.category_code = :category");
            params.put("category", filter.categoryCode());
        }
        if (filter.counterpartyKey() != null) {
            sql.append(" and p.counterparty_key = :key");
            params.put("key", filter.counterpartyKey());
        }
        if (filter.q() != null) {
            // Name and purpose cover bank rows; INN, the manual note and the amount cover facts typed in by hand.
            sql.append(" and (p.counterparty_name ilike :q or p.purpose ilike :q or p.counterparty_inn ilike :q "
                    + "or p.manual_note ilike :q or cast(p.amount as text) like :q)");
            params.put("q", like(filter.q().strip()));
        }
        return sql.toString();
    }

    public Optional<Payment> findPaymentForCompany(CompanyScope scope, UUID paymentId) {
        return jdbc.sql("select " + PAYMENT_COLUMNS + " from payments where id = :id and company_id = :company")
                .param("id", paymentId).param("company", scope.companyId())
                .query(LedgerRepository::payment).optional();
    }

    public Optional<Payment> lockPaymentForCompany(CompanyScope scope, UUID paymentId) {
        return jdbc.sql("select " + PAYMENT_COLUMNS + " from payments where id = :id and company_id = :company "
                        + "for update")
                .param("id", paymentId).param("company", scope.companyId())
                .query(LedgerRepository::payment).optional();
    }

    public Optional<PaymentView> findPaymentView(CompanyScope scope, UUID accountId, UUID paymentId) {
        return jdbc.sql("select " + PAYMENT_VIEW_COLUMNS + " from payments p where p.id = :id "
                        + "and p.company_id = :company and p.account_id = :account")
                .param("id", paymentId).param("company", scope.companyId()).param("account", accountId)
                .query(LedgerRepository::paymentView).optional();
    }

    public List<PaymentView> paymentViews(CompanyScope scope, List<UUID> paymentIds) {
        return jdbc.sql("select " + PAYMENT_VIEW_COLUMNS + " from payments p where p.company_id = :company "
                        + "and p.id in (:ids)")
                .param("company", scope.companyId()).param("ids", paymentIds)
                .query(LedgerRepository::paymentView).list();
    }

    public PageResult<Counterparty> counterparties(CompanyScope scope, UUID accountId, String q, PageQuery page) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("company", scope.companyId());
        params.put("account", accountId);
        StringBuilder filter = new StringBuilder("p.company_id = :company and p.account_id = :account "
                + "and p.counterparty_key is not null and not exists (select 1 from payment_voids v where v.payment_id=p.id)");
        if (q != null && !q.isBlank()) {
            filter.append(" and (p.counterparty_name ilike :q or p.counterparty_inn ilike :q)");
            params.put("q", like(q.strip()));
        }
        long total = jdbc.sql("select count(distinct p.counterparty_key) from payments p where " + filter)
                .params(params).query(Long.class).single();
        params.put("limit", page.size());
        params.put("offset", page.offset());
        List<Counterparty> items = jdbc.sql("select cp.key, cp.inn, cp.name from (select distinct on "
                        + "(p.counterparty_key) p.counterparty_key as key, p.counterparty_inn as inn, "
                        + "p.counterparty_name as name from payments p where " + filter
                        + " order by p.counterparty_key, p.operation_date desc, p.id desc) cp "
                        + "order by cp.key limit :limit offset :offset")
                .params(params)
                .query((rs, row) -> new Counterparty(rs.getString("key"), rs.getString("inn"),
                        rs.getString("name")))
                .list();
        return PageResult.of(items, total);
    }

    private static String like(String value) {
        return "%" + value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** Manual facts already recorded in the period; used only for import duplicate warnings. */
    public List<Payment> manualPaymentsBetween(CompanyScope scope, UUID accountId, LocalDate from, LocalDate to) {
        return jdbc.sql("select " + PAYMENT_COLUMNS + " from payments where company_id = :company "
                        + "and account_id = :account and source = 'MANUAL' and not exists (select 1 from payment_voids v where v.payment_id=payments.id) and operation_date between :from and :to "
                        + "order by operation_date, id")
                .param("company", scope.companyId()).param("account", accountId).param("from", from).param("to", to)
                .query(LedgerRepository::payment).list();
    }

    /** Facts known strictly before the cutoff; the date bound excludes future-dated rows. */
    public List<Payment> paymentsKnownBefore(CompanyScope scope, UUID accountId, LocalDate from, LocalDate to,
                                             Instant knownBefore) {
        return jdbc.sql("select " + PAYMENT_COLUMNS + " from payments where company_id = :company "
                        + "and account_id = :account and operation_date between :from and :to and known_at < :known "
                        + "and not exists (select 1 from payment_voids v where v.payment_id=payments.id "
                        + "and v.voided_at < :known) "
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

    /** Immutable automatic classifications of scoring facts; later USER overrides are deliberately not selected. */
    public List<CategoryRevision> initialRevisionsKnownBefore(CompanyScope scope, UUID accountId, LocalDate from,
                                                              LocalDate to, Instant knownBefore) {
        return jdbc.sql("select r.payment_id, r.category_code, r.category_source, r.rule_version, r.known_at, "
                        + "r.actor_id, r.version from payment_revisions r "
                        + "join payments p on p.id = r.payment_id and p.company_id = r.company_id "
                        + "where r.company_id = :company and p.account_id = :account and r.version = 0 "
                        + "and p.operation_date between :from and :to and r.known_at < :known order by r.payment_id")
                .param("company", scope.companyId()).param("account", accountId).param("from", from).param("to", to)
                .param("known", Timestamp.from(knownBefore))
                .query((rs, row) -> new CategoryRevision(rs.getObject("payment_id", UUID.class),
                        rs.getString("category_code"), CategorySource.valueOf(rs.getString("category_source")),
                        rs.getString("rule_version"), rs.getTimestamp("known_at").toInstant(),
                        rs.getObject("actor_id", UUID.class), rs.getLong("version")))
                .list();
    }

    /** Evidence that an automatic categorizer version was already used on this account at the origin. */
    public List<String> ruleVersionsKnownBefore(CompanyScope scope, UUID accountId, LocalDate asOf, Instant knownBefore) {
        return jdbc.sql("select distinct r.rule_version from payment_revisions r "
                        + "join payments p on p.id = r.payment_id and p.company_id = r.company_id "
                        + "where r.company_id = :company and p.account_id = :account and p.operation_date <= :asOf "
                        + "and p.known_at < :known and r.known_at < :known and r.category_source <> 'USER' "
                        + "and r.rule_version is not null "
                        + "and not exists (select 1 from payment_voids v where v.payment_id=p.id and v.voided_at < :known) "
                        + "order by r.rule_version")
                .param("company", scope.companyId()).param("account", accountId).param("asOf", asOf)
                .param("known", Timestamp.from(knownBefore)).query(String.class).list();
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

    /** Newest append-only assertions first; overlapping and UNKNOWN records remain visible. */
    public PageResult<CompletenessRange> completenessPage(CompanyScope scope, UUID accountId, PageQuery page) {
        long total = jdbc.sql("select count(*) from completeness_ranges "
                        + "where company_id = :company and account_id = :account")
                .param("company", scope.companyId()).param("account", accountId)
                .query(Long.class).single();
        List<CompletenessRange> items = jdbc.sql("select id, account_id, from_date, to_date, completeness, "
                        + "reason, known_at, actor_id from completeness_ranges "
                        + "where company_id = :company and account_id = :account "
                        + "order by known_at desc, id desc limit :limit offset :offset")
                .param("company", scope.companyId()).param("account", accountId)
                .param("limit", page.size()).param("offset", page.offset())
                .query(LedgerRepository::completenessRange).list();
        return PageResult.of(items, total);
    }

    /** Only active facts in the requested interval; voided manual entries must not inflate the warning. */
    public long emptyPurposeCount(CompanyScope scope, UUID accountId, LocalDate from, LocalDate to) {
        return jdbc.sql("select count(*) from payments p where p.company_id = :company "
                        + "and p.account_id = :account and p.operation_date between :from and :to "
                        + "and (p.purpose is null or btrim(p.purpose) = '') "
                        + "and not exists (select 1 from payment_voids v where v.payment_id = p.id)")
                .param("company", scope.companyId()).param("account", accountId)
                .param("from", from).param("to", to)
                .query(Long.class).single();
    }

    public List<CompletenessRange> completenessKnownBefore(CompanyScope scope, UUID accountId, Instant knownBefore) {
        return jdbc.sql("select id, account_id, from_date, to_date, completeness, reason, known_at, actor_id "
                        + "from completeness_ranges where company_id = :company and account_id = :account "
                        + "and known_at < :known order by known_at, id")
                .param("company", scope.companyId()).param("account", accountId)
                .param("known", Timestamp.from(knownBefore))
                .query(LedgerRepository::completenessRange).list();
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
                rs.getTimestamp("known_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                PaymentSource.valueOf(rs.getString("source")), rs.getObject("created_by", UUID.class),
                rs.getString("manual_note"));
    }

    private static PaymentView paymentView(ResultSet rs, int row) throws SQLException {
        Timestamp voided = rs.getTimestamp("voided_at");
        return new PaymentView(payment(rs, row), rs.getBigDecimal("allocated_amount"),
                voided == null ? null : voided.toInstant(), rs.getString("void_reason"),
                rs.getObject("replacement_id", UUID.class), DayStatus.valueOf(rs.getString("day_status")));
    }

    private static CompletenessRange completenessRange(ResultSet rs, int row) throws SQLException {
        return new CompletenessRange(rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class), rs.getObject("from_date", LocalDate.class),
                rs.getObject("to_date", LocalDate.class), Completeness.valueOf(rs.getString("completeness")),
                rs.getString("reason"), rs.getTimestamp("known_at").toInstant(),
                rs.getObject("actor_id", UUID.class));
    }

    private static BalanceAnchor anchor(ResultSet rs, int row) throws SQLException {
        return new BalanceAnchor(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getObject("date", LocalDate.class), rs.getBigDecimal("balance"),
                AnchorSource.valueOf(rs.getString("source")), rs.getString("reason"),
                rs.getTimestamp("known_at").toInstant(), rs.getObject("actor_id", UUID.class),
                rs.getObject("supersedes_id", UUID.class));
    }
}
