package ru.cashflow.orchestrator.features.results.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.companies.model.CompanyScope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ResultQueryRepository {
    private final JdbcClient jdbc;

    public ResultQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<SavedResult> list(
            CompanyScope scope, UUID account, boolean backtest, int limit, int offset) {
        if (!backtest) {
            return jdbc.sql(forecastSelect() + " where f.company_id=:company and f.account_id=:account "
                            + "order by latest_forecast desc,f.as_of desc,f.input_version desc,s.created_at desc,"
                            + "j.created_at desc,j.enqueue_sequence desc limit :limit offset :offset")
                    .param("company", scope.companyId()).param("account", account).param("limit", limit)
                    .param("offset", offset).query((rs, row) -> new SavedResult(rs.getString("payload"),
                            rs.getLong("input_version"), rs.getTimestamp("created_at").toInstant(),
                            rs.getBoolean("latest_forecast"))).list();
        }
        return jdbc.sql(
                        "select response_json::text as payload, input_version, created_at from "
                                + table(backtest)
                                + " where company_id=:company and account_id=:account order by "
                                + (backtest ? "last_origin" : "as_of")
                                + " desc, input_version desc, created_at desc, id desc limit :limit"
                                + " offset :offset")
                .param("company", scope.companyId())
                .param("account", account)
                .param("limit", limit)
                .param("offset", offset)
                .query(
                        (rs, row) ->
                                new SavedResult(
                                        rs.getString("payload"),
                                        rs.getLong("input_version"),
                                        rs.getTimestamp("created_at").toInstant(), true))
                .list();
    }

    public Optional<SavedResult> find(CompanyScope scope, UUID account, UUID id, boolean backtest) {
        if (!backtest) {
            return jdbc.sql(forecastSelect() + " where f.company_id=:company and f.account_id=:account and f.id=:id")
                    .param("company", scope.companyId()).param("account", account).param("id", id)
                    .query((rs, row) -> new SavedResult(rs.getString("payload"), rs.getLong("input_version"),
                            rs.getTimestamp("created_at").toInstant(), rs.getBoolean("latest_forecast"))).optional();
        }
        return jdbc.sql(
                        "select response_json::text as payload, input_version, created_at from "
                                + table(backtest)
                                + " where company_id=:company and account_id=:account and id=:id")
                .param("company", scope.companyId())
                .param("account", account)
                .param("id", id)
                .query(
                        (rs, row) ->
                                new SavedResult(
                                        rs.getString("payload"),
                                        rs.getLong("input_version"),
                                        rs.getTimestamp("created_at").toInstant(), true))
                .optional();
    }

    public Optional<SavedResult> compatibleBacktest(
            CompanyScope scope, UUID account, String algorithm, int window, String forecastScope) {
        return jdbc.sql(
                        "select response_json::text as payload,input_version,created_at from"
                                + " backtests where company_id=:company and account_id=:account and"
                                + " algorithm_version=:algorithm and window_weeks=:window and"
                                + " scope=:scope order by last_origin desc,input_version"
                                + " desc,created_at desc,id desc limit 1")
                .param("company", scope.companyId())
                .param("account", account)
                .param("algorithm", algorithm)
                .param("window", window)
                .param("scope", forecastScope)
                .query(
                        (rs, row) ->
                                new SavedResult(
                                        rs.getString("payload"),
                                        rs.getLong("input_version"),
                                        rs.getTimestamp("created_at").toInstant(), true))
                .optional();
    }

    private String table(boolean backtest) {
        return backtest ? "backtests" : "forecasts";
    }

    private static String forecastSelect() {
        // A published head wins over a later completion of an old calculation. Fallback covers legacy rows only.
        return "select f.response_json::text as payload,f.input_version,f.created_at, "
                + "case when h.forecast_id is not null then h.forecast_id=f.id else not exists ("
                + "select 1 from forecasts newer join snapshots ns on ns.id=newer.snapshot_id "
                + "join jobs nj on nj.id=newer.job_id where newer.company_id=f.company_id and newer.account_id=f.account_id "
                + "and (newer.as_of,newer.input_version,ns.created_at,nj.created_at,nj.enqueue_sequence) > "
                + "(f.as_of,f.input_version,s.created_at,j.created_at,j.enqueue_sequence)) end as latest_forecast "
                + "from forecasts f join snapshots s on s.id=f.snapshot_id join jobs j on j.id=f.job_id "
                + "left join forecast_alert_heads h on h.company_id=f.company_id and h.account_id=f.account_id";
    }

    public record SavedResult(String payload, long inputVersion, Instant createdAt, boolean latestForecast) {}
}
