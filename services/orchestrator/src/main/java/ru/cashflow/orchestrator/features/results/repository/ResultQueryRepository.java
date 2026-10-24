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
                            + "order by " + ForecastSelectionSql.ORDER_BY + " limit :limit offset :offset")
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
                + ForecastSelectionSql.IS_LATEST + " as latest_forecast " + ForecastSelectionSql.FROM;
    }

    public record SavedResult(String payload, long inputVersion, Instant createdAt, boolean latestForecast) {}
}
