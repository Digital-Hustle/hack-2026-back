package ru.cashflow.orchestrator.features.planfact.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.DailyActual;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.DailyPlan;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.DailyUnallocated;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.OverduePlan;
import ru.cashflow.orchestrator.features.planfact.model.PlanFactTypes.UnplannedFact;

/**
 * One read model, no writes. The plan side uses the plan's full amount and exposes settled/remaining separately;
 * the fact side uses the immutable payment, so a split allocation never duplicates the cash movement.
 */
@Repository
public class PlanFactRepository {

    private static final String ACTIVE_PAYMENT =
            "not exists (select 1 from payment_voids v where v.payment_id = p.id) ";

    private static final String ACTIVE_ALLOCATIONS = "select company_id, account_id, payment_id, sum(amount) as amount "
            + "from allocations where company_id = :company and account_id = :account and reversed_at is null "
            + "group by company_id, account_id, payment_id";

    private final JdbcClient jdbc;

    public PlanFactRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DailyPlan> planned(CompanyScope scope, UUID accountId, LocalDate from, LocalDate to) {
        return jdbc.sql("select due_date as day, direction, sum(amount) as planned, sum(settled_amount) as settled, "
                        + "sum(case when status = 'CANCELLED' then 0 else amount - settled_amount end) as remaining "
                        + "from expected_payments where company_id = :company and account_id = :account "
                        + "and due_date between :from and :to and status <> 'CANCELLED' "
                        + "group by due_date, direction order by due_date, direction")
                .param("company", scope.companyId()).param("account", accountId).param("from", from).param("to", to)
                .query((rs, row) -> new DailyPlan(rs.getObject("day", LocalDate.class),
                        Direction.valueOf(rs.getString("direction")), rs.getBigDecimal("planned"),
                        rs.getBigDecimal("settled"), rs.getBigDecimal("remaining"))).list();
    }

    public List<DailyActual> actual(CompanyScope scope, UUID accountId, LocalDate from, LocalDate to) {
        return jdbc.sql("select operation_date as day, direction, sum(amount) as amount from payments p "
                        + "where company_id = :company and account_id = :account "
                        + "and " + ACTIVE_PAYMENT
                        + "and operation_date between :from and :to group by operation_date, direction "
                        + "order by operation_date, direction")
                .param("company", scope.companyId()).param("account", accountId).param("from", from).param("to", to)
                .query((rs, row) -> new DailyActual(rs.getObject("day", LocalDate.class),
                        Direction.valueOf(rs.getString("direction")), rs.getBigDecimal("amount"))).list();
    }

    public List<DailyUnallocated> unallocated(CompanyScope scope, UUID accountId, LocalDate from, LocalDate to) {
        return jdbc.sql("select p.operation_date as day, p.direction, "
                        + "sum(greatest(0, p.amount - coalesce(a.amount, 0))) as amount "
                        + "from payments p left join (" + ACTIVE_ALLOCATIONS + ") a on a.payment_id = p.id "
                        + "where p.company_id = :company and p.account_id = :account "
                        + "and " + ACTIVE_PAYMENT
                        + "and p.operation_date between :from and :to "
                        + "group by p.operation_date, p.direction "
                        + "having sum(greatest(0, p.amount - coalesce(a.amount, 0))) > 0 "
                        + "order by p.operation_date, p.direction")
                .param("company", scope.companyId()).param("account", accountId).param("from", from).param("to", to)
                .query((rs, row) -> new DailyUnallocated(rs.getObject("day", LocalDate.class),
                        Direction.valueOf(rs.getString("direction")), rs.getBigDecimal("amount"))).list();
    }

    public List<OverduePlan> overduePlans(CompanyScope scope, UUID accountId, LocalDate from, LocalDate to,
                                           LocalDate today) {
        return jdbc.sql("select id, title, direction, due_date, amount, settled_amount from expected_payments "
                        + "where company_id = :company and account_id = :account "
                        + "and status in ('OPEN', 'PARTIALLY_SETTLED') and due_date between :from and :to "
                        + "and due_date < :today and amount > settled_amount order by due_date, id")
                .param("company", scope.companyId()).param("account", accountId).param("from", from).param("to", to)
                .param("today", today)
                .query((rs, row) -> new OverduePlan(rs.getObject("id", UUID.class), rs.getString("title"),
                        Direction.valueOf(rs.getString("direction")), rs.getObject("due_date", LocalDate.class),
                        rs.getBigDecimal("amount"), rs.getBigDecimal("settled_amount"),
                        rs.getBigDecimal("amount").subtract(rs.getBigDecimal("settled_amount")))).list();
    }

    public List<UnplannedFact> unplannedFacts(CompanyScope scope, UUID accountId, LocalDate from, LocalDate to) {
        return jdbc.sql("select p.id, p.operation_date, p.direction, p.amount, p.counterparty_name, p.category_code, "
                        + "greatest(0, p.amount - coalesce(a.amount, 0)) as unallocated "
                        + "from payments p left join (" + ACTIVE_ALLOCATIONS + ") a on a.payment_id = p.id "
                        + "where p.company_id = :company and p.account_id = :account "
                        + "and " + ACTIVE_PAYMENT
                        + "and p.operation_date between :from and :to and p.amount > coalesce(a.amount, 0) "
                        + "order by p.operation_date, p.id")
                .param("company", scope.companyId()).param("account", accountId).param("from", from).param("to", to)
                .query((rs, row) -> new UnplannedFact(rs.getObject("id", UUID.class),
                        rs.getObject("operation_date", LocalDate.class),
                        Direction.valueOf(rs.getString("direction")), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("unallocated"), rs.getString("counterparty_name"),
                        rs.getString("category_code"))).list();
    }
}
