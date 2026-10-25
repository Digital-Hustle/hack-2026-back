package ru.cashflow.orchestrator.features.analytics.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.analytics.model.AnalyticsTypes.Aggregate;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;

/** All aggregation is performed in PostgreSQL, never over a paginated payments response. */
@Repository
public class AnalyticsRepository {

    private static final String FILTER = "p.company_id = :company and p.account_id = :account "
            + "and p.operation_date between :from and :to "
            + "and not exists (select 1 from payment_voids v where v.payment_id = p.id)";

    private final JdbcClient jdbc;

    public AnalyticsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Aggregate> byCategory(CompanyScope scope, UUID accountId, LocalDate from, LocalDate to,
                                      Direction direction) {
        Map<String, Object> params = parameters(scope, accountId, from, to, direction);
        String sql = "select p.direction, p.category_code as group_key, "
                + "sum(p.amount) as amount, count(*) as payment_count "
                + "from payments p where " + FILTER + directionFilter(direction)
                + " group by p.direction, p.category_code "
                + "order by p.direction, amount desc, p.category_code";
        return jdbc.sql(sql).params(params).query((rs, row) -> new Aggregate(
                Direction.valueOf(rs.getString("direction")), rs.getString("group_key"), null, null,
                rs.getBigDecimal("amount"), rs.getLong("payment_count"))).list();
    }

    public List<Aggregate> byCounterparty(CompanyScope scope, UUID accountId, LocalDate from, LocalDate to,
                                          Direction direction) {
        Map<String, Object> params = parameters(scope, accountId, from, to, direction);
        String sql = "with filtered as ("
                + "select p.id, p.operation_date, p.direction, p.counterparty_key, p.counterparty_inn, "
                + "p.counterparty_name, p.amount from payments p where " + FILTER + directionFilter(direction)
                + "), aggregates as ("
                + "select direction, counterparty_key, sum(amount) as amount, count(*) as payment_count "
                + "from filtered group by direction, counterparty_key"
                + ") select a.direction, a.counterparty_key as group_key, "
                + "last_seen.counterparty_inn as inn, last_seen.counterparty_name as name, "
                + "a.amount, a.payment_count from aggregates a "
                + "left join lateral ("
                + "select f.counterparty_inn, f.counterparty_name from filtered f "
                + "where f.direction = a.direction "
                + "and f.counterparty_key is not distinct from a.counterparty_key "
                + "order by f.operation_date desc, f.id desc limit 1"
                + ") last_seen on true "
                + "order by a.direction, a.amount desc, a.counterparty_key nulls last";
        return jdbc.sql(sql).params(params).query((rs, row) -> new Aggregate(
                Direction.valueOf(rs.getString("direction")), rs.getString("group_key"),
                rs.getString("inn"), rs.getString("name"),
                rs.getBigDecimal("amount"), rs.getLong("payment_count"))).list();
    }

    private static String directionFilter(Direction direction) {
        return direction == null ? "" : " and p.direction = :direction";
    }

    private static Map<String, Object> parameters(CompanyScope scope, UUID accountId, LocalDate from,
                                                   LocalDate to, Direction direction) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("company", scope.companyId());
        params.put("account", accountId);
        params.put("from", from);
        params.put("to", to);
        if (direction != null) {
            params.put("direction", direction.name());
        }
        return params;
    }
}
