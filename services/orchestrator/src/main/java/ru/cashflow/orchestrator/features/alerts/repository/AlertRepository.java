package ru.cashflow.orchestrator.features.alerts.repository;

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

import ru.cashflow.orchestrator.features.alerts.model.AlertTypes.Code;
import ru.cashflow.orchestrator.features.alerts.model.AlertTypes.Draft;
import ru.cashflow.orchestrator.features.alerts.model.AlertTypes.GapSource;
import ru.cashflow.orchestrator.features.alerts.model.AlertTypes.SavedAlert;
import ru.cashflow.orchestrator.features.companies.model.CompanyScope;
import ru.cashflow.orchestrator.features.forecast.model.ForecastResult;

@Repository
public class AlertRepository {

    private static final String ALERT_SELECT = "select a.*, f.input_version, "
            + "coalesce(h.forecast_id = a.forecast_id, false) as latest_forecast from alerts a "
            + "join forecasts f on f.id = a.forecast_id "
            + "left join forecast_alert_heads h on h.company_id = a.company_id and h.account_id = a.account_id ";

    private final JdbcClient jdbc;

    public AlertRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Serialises publications with each other and with dataVersion updates; never uses completion time for ordering. */
    public long lockPublication(UUID companyId) {
        return jdbc.sql("select data_version from companies where id=:company for update")
                .param("company", companyId).query(Long.class).single();
    }

    public void publish(ForecastResult result, List<Draft> drafts, Instant now) {
        long currentVersion = lockPublication(result.companyId());
        for (Draft draft : drafts) {
            jdbc.sql("insert into alerts (id,company_id,account_id,forecast_id,gap_id,plan_id,event_key,code,title,message,"
                            + "from_date,to_date,amount,probability,active,created_at) "
                            + "values(:id,:c,:a,:f,:g,:p,:key,:code,:title,:message,:from,:to,:amount,:probability,false,:now)")
                    .param("id", UUID.randomUUID()).param("c", result.companyId()).param("a", result.accountId())
                    .param("f", result.id()).param("g", draft.gapId()).param("p", draft.planId())
                    .param("key", draft.eventKey()).param("code", draft.code().name()).param("title", draft.title())
                    .param("message", draft.message()).param("from", draft.fromDate()).param("to", draft.toDate())
                    .param("amount", draft.amount()).param("probability", draft.probability()).param("now", Timestamp.from(now))
                    .update();
        }
        if (currentVersion != result.inputDataVersion()) {
            return;
        }
        int promoted = jdbc.sql("insert into forecast_alert_heads (company_id,account_id,forecast_id) values(:c,:a,:f) "
                        + "on conflict (company_id,account_id) do update set forecast_id=excluded.forecast_id where "
                        + "(select (fresh.as_of,fresh.input_version,fs.created_at,fj.created_at,fj.enqueue_sequence) > "
                        + "(prior.as_of,prior.input_version,ps.created_at,pj.created_at,pj.enqueue_sequence) "
                        + "from forecasts fresh join snapshots fs on fs.id=fresh.snapshot_id join jobs fj on fj.id=fresh.job_id "
                        + "cross join forecasts prior join snapshots ps on ps.id=prior.snapshot_id join jobs pj on pj.id=prior.job_id "
                        + "where fresh.id=excluded.forecast_id and prior.id=forecast_alert_heads.forecast_id)")
                .param("c", result.companyId()).param("a", result.accountId()).param("f", result.id()).update();
        if (promoted == 1) {
            jdbc.sql("update alerts set active=(forecast_id=:f) where company_id=:c and account_id=:a")
                    .param("f", result.id()).param("c", result.companyId()).param("a", result.accountId()).update();
        }
    }

    public List<SavedAlert> list(CompanyScope scope, UUID account, boolean activeOnly, int limit, int offset) {
        return jdbc.sql(ALERT_SELECT + "where a.company_id=:c and a.account_id=:a and (not :onlyActive or a.active) "
                        + "order by a.active desc,a.created_at desc,a.id limit :limit offset :offset")
                .param("c", scope.companyId()).param("a", account).param("onlyActive", activeOnly)
                .param("limit", limit).param("offset", offset).query(AlertRepository::alert).list();
    }

    public Optional<SavedAlert> find(CompanyScope scope, UUID account, UUID id) {
        return jdbc.sql(ALERT_SELECT + "where a.id=:id and a.company_id=:c and a.account_id=:a")
                .param("id", id).param("c", scope.companyId()).param("a", account).query(AlertRepository::alert).optional();
    }

    /** coalesce under PostgreSQL's row lock preserves the first acknowledgement, even for concurrent requests. */
    public void acknowledge(CompanyScope scope, UUID account, UUID id, Instant now) {
        jdbc.sql("update alerts set acknowledged_at=coalesce(acknowledged_at,:now), "
                        + "acknowledged_by=coalesce(acknowledged_by,:user) where id=:id and company_id=:c and account_id=:a")
                .param("now", Timestamp.from(now)).param("user", scope.userId()).param("id", id)
                .param("c", scope.companyId()).param("a", account).update();
    }

    public Optional<GapSource> gapSource(CompanyScope scope, UUID account, UUID forecast, UUID gap) {
        return jdbc.sql("select f.id,f.snapshot_id,f.response_json::text as response,s.payload::text as snapshot,"
                        + "f.created_at,f.input_version,coalesce(h.forecast_id=f.id,false) as latest_forecast "
                        + "from forecasts f join forecast_gaps g on g.forecast_id=f.id "
                        + "and g.company_id=f.company_id and g.account_id=f.account_id "
                        + "join snapshots s on s.id=f.snapshot_id and s.company_id=f.company_id and s.account_id=f.account_id "
                        + "left join forecast_alert_heads h on h.company_id=f.company_id and h.account_id=f.account_id "
                        + "where f.id=:f and g.id=:g and f.company_id=:c and f.account_id=:a")
                .param("f", forecast).param("g", gap).param("c", scope.companyId()).param("a", account)
                .query((rs, row) -> new GapSource(rs.getObject("id", UUID.class), rs.getObject("snapshot_id", UUID.class),
                        rs.getString("response"), rs.getString("snapshot"), rs.getTimestamp("created_at").toInstant(),
                        rs.getLong("input_version"), rs.getBoolean("latest_forecast"))).optional();
    }

    private static SavedAlert alert(ResultSet rs, int row) throws SQLException {
        Timestamp acknowledged = rs.getTimestamp("acknowledged_at");
        return new SavedAlert(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("account_id", UUID.class), rs.getObject("forecast_id", UUID.class), rs.getObject("gap_id", UUID.class),
                rs.getObject("plan_id", UUID.class), Code.valueOf(rs.getString("code")), rs.getString("title"),
                rs.getString("message"), rs.getObject("from_date", LocalDate.class), rs.getObject("to_date", LocalDate.class),
                rs.getBigDecimal("amount"), rs.getBigDecimal("probability"), rs.getBoolean("active"),
                acknowledged == null ? null : acknowledged.toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getLong("input_version"), rs.getBoolean("latest_forecast"));
    }
}
