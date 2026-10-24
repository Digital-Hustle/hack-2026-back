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
                                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public Optional<SavedResult> find(CompanyScope scope, UUID account, UUID id, boolean backtest) {
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
                                        rs.getTimestamp("created_at").toInstant()))
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
                                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    private String table(boolean backtest) {
        return backtest ? "backtests" : "forecasts";
    }

    public record SavedResult(String payload, long inputVersion, Instant createdAt) {}
}
