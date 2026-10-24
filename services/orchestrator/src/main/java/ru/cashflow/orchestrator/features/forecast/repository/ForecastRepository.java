package ru.cashflow.orchestrator.features.forecast.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ru.cashflow.orchestrator.features.forecast.model.ForecastResult;

@Repository
public class ForecastRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ForecastRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Must run inside the transaction that also marks the job SUCCEEDED. forecasts.job_id is unique. */
    public void insert(ForecastResult r, String settingsJson, String responseJson, Instant now) {
        jdbc.sql("insert into forecasts (id, company_id, account_id, job_id, snapshot_id, as_of, horizon, "
                        + "algorithm_version, input_version, scope, quality, start_balance, start_balance_date, "
                        + "expected_minimum_balance, first_expected_gap_date, max_expected_shortfall, settings_json, "
                        + "risk_json, summary_json, response_json, created_at) values (:id, :company, :account, :job, "
                        + ":snapshot, :asOf, 30, :algorithm, :input, :scope, :quality, :start, :startDate, :min, :gap, "
                        + ":shortfall, cast(:settings as jsonb), cast(:risk as jsonb), cast(:summary as jsonb), "
                        + "cast(:response as jsonb), :now)")
                .param("id", r.id()).param("company", r.companyId()).param("account", r.accountId())
                .param("job", r.jobId()).param("snapshot", r.snapshotId()).param("asOf", r.asOfDate())
                .param("algorithm", r.algorithmVersion()).param("input", r.inputDataVersion())
                .param("scope", r.scope()).param("quality", r.quality()).param("start", r.startBalance())
                .param("startDate", r.startBalanceDate()).param("min", r.expectedMinimumBalance())
                .param("gap", r.firstExpectedGapDate()).param("shortfall", r.maxExpectedShortfall())
                .param("settings", settingsJson).param("risk", write(risk(r.risk())))
                .param("summary", write(summary(r))).param("response", responseJson)
                .param("now", Timestamp.from(now)).update();
        for (var d : r.days()) {
            jdbc.sql("insert into forecast_days (forecast_id, date, planned_in, planned_out, recurring_in, "
                            + "recurring_out, residual_in, residual_out, inflow, outflow, balance, balance_p10, "
                            + "balance_p50, balance_p90, p_below_zero, p_below_buffer) values (:id, :date, :pi, :po, "
                            + ":ri, :ro, :si, :so, :in, :out, :balance, :p10, :p50, :p90, :pz, :pb)")
                    .param("id", r.id()).param("date", d.date()).param("pi", d.plannedIn()).param("po", d.plannedOut())
                    .param("ri", d.recurringIn()).param("ro", d.recurringOut()).param("si", d.residualIn())
                    .param("so", d.residualOut()).param("in", d.inflow()).param("out", d.outflow())
                    .param("balance", d.balance()).param("p10", d.balanceP10()).param("p50", d.balanceP50())
                    .param("p90", d.balanceP90()).param("pz", d.pBelowZero()).param("pb", d.pBelowBuffer()).update();
        }
        for (var g : r.gaps()) {
            jdbc.sql("insert into forecast_gaps (id, forecast_id, company_id, account_id, kind, from_date, to_date, "
                            + "threshold, point_min, point_shortfall, probability, shortfall_p50, shortfall_p90, "
                            + "shortfall_basis, risk_availability) values (:gid, :id, :company, :account, :kind, :from, "
                            + ":to, :threshold, :min, :shortfall, :p, :p50, :p90, :basis, :availability)")
                    .param("gid", g.id()).param("id", r.id()).param("company", r.companyId())
                    .param("account", r.accountId()).param("kind", g.kind()).param("from", g.fromDate())
                    .param("to", g.toDate()).param("threshold", g.threshold()).param("min", g.expectedMinimumBalance())
                    .param("shortfall", g.expectedShortfall()).param("p", g.probability())
                    .param("p50", g.shortfallP50()).param("p90", g.shortfallP90()).param("basis", g.shortfallBasis())
                    .param("availability", g.riskEstimateAvailability()).update();
        }
    }

    private static Map<String, Object> risk(ForecastResult.Risk risk) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("availability", risk.availability());
        m.put("method", risk.method());
        m.put("probability", risk.probability() == null ? null : risk.probability().toPlainString());
        m.put("scenarioCount", risk.scenarioCount());
        m.put("calibrationOriginCount", risk.calibrationOriginCount());
        m.put("probabilityCalibrated", risk.probabilityCalibrated());
        return m;
    }

    private static Map<String, Object> summary(ForecastResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("expectedMinimumBalance", r.expectedMinimumBalance().toPlainString());
        m.put("firstExpectedGapDate", r.firstExpectedGapDate() == null ? null : r.firstExpectedGapDate().toString());
        m.put("maxExpectedShortfall", r.maxExpectedShortfall().toPlainString());
        m.put("gapCount", r.gaps().size());
        return m;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
