package ru.cashflow.orchestrator.features.forecast.repository;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.jobs.model.JobTypes.Job;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

@Repository
public class BacktestRepository {
    private final JdbcClient jdbc;

    public BacktestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Job job, JsonNode result, String body, Instant now) {
        jdbc.sql(
                        "insert into"
                            + " backtests(id,company_id,account_id,job_id,snapshot_id,input_version,algorithm_version,scope,window_weeks,first_origin,last_origin,step_days,status,response_json,created_at)"
                            + " values(:id,:c,:a,:j,:s,:v,:algorithm,:scope,:window,:first,:last,:step,:status,cast(:body"
                            + " as jsonb),:now)")
                .param("id", job.resultId())
                .param("c", job.companyId())
                .param("a", job.accountId())
                .param("j", job.id())
                .param("s", job.snapshotId())
                .param("v", result.path("inputDataVersion").asLong())
                .param("algorithm", result.path("algorithmVersion").asText())
                .param("scope", result.path("scope").asText())
                .param("window", result.path("windowWeeks").asInt())
                .param("first", LocalDate.parse(result.path("firstOrigin").asText()))
                .param("last", LocalDate.parse(result.path("lastOrigin").asText()))
                .param("step", result.path("stepDays").asInt())
                .param("status", result.path("status").asText())
                .param("body", body)
                .param("now", Timestamp.from(now))
                .update();
        for (var origin : result.path("origins")) {
            jdbc.sql(
                            "insert into"
                                + " backtest_origins(backtest_id,as_of,status,reason,mae_balance,evaluated_days)"
                                + " values(:id,:date,:status,:reason,:mae,:days)")
                    .param("id", job.resultId())
                    .param("date", LocalDate.parse(origin.path("asOfDate").asText()))
                    .param("status", origin.path("status").asText())
                    .param(
                            "reason",
                            origin.path("reason").isNull() ? null : origin.path("reason").asText())
                    .param(
                            "mae",
                            origin.path("maeBalance").isNull()
                                    ? null
                                    : new java.math.BigDecimal(origin.path("maeBalance").asText()))
                    .param("days", origin.path("evaluatedDays").asInt())
                    .update();
        }
    }
}
