package ru.cashflow.orchestrator.units;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.features.forecast.command.BacktestResultValidator;
import ru.cashflow.orchestrator.features.forecast.gateway.ForecastException;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.Job;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.JobStatus;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.JobType;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

class BacktestResultValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Job job =
            new Job(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    JobType.BACKTEST,
                    JobStatus.RUNNING,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    0,
                    null,
                    "worker",
                    Instant.now().plusSeconds(60),
                    1,
                    3,
                    Instant.now(),
                    UUID.randomUUID(),
                    Instant.now(),
                    Instant.now(),
                    null);

    @Test
    void rejectsForeignIdentityInvalidGridAndInconsistentAggregates() {
        assertThatCode(() -> BacktestResultValidator.validate(valid(), input(), job))
                .doesNotThrowAnyException();
        reject(result -> result.put("jobId", UUID.randomUUID().toString()));
        reject(result -> result.put("snapshotId", UUID.randomUUID().toString()));
        reject(result -> result.put("evaluatedDays", 29));
        reject(result -> result.withArray("origins").removeAll());
        reject(
                result ->
                        ((ObjectNode) result.withArray("origins").get(0))
                                .put("asOfDate", "2026-09-02"));
        reject(
                result ->
                        ((ObjectNode) result.withArray("horizons").get(0))
                                .put("evaluatedCount", 2));
        reject(result -> result.put("maeBalance", "-1.00"));
        reject(result -> ((ObjectNode) result.path("gapMetrics")).put("precision", 1.0));
        reject(result -> ((ObjectNode) result.path("riskMetrics")).put("brierScore", 2.0));
    }

    @Test
    void rejectsGapMetricsWithoutMatchesAndBinAveragesOutsideTheirBin() {
        reject(result -> ((ObjectNode) result.path("gapMetrics")).put("medianLeadDays", 10));
        reject(result -> ((ObjectNode) result.path("gapMetrics")).put("dateMaeDays", 1));
        reject(result -> ((ObjectNode) result.path("gapMetrics")).put("shortfallMae", "1.00"));
        reject(
                result -> {
                    var risk = (ObjectNode) result.path("riskMetrics");
                    risk.put("evaluatedCount", 1);
                    risk.put("brierScore", 0.2);
                    risk.put("interval80Coverage", 0.8);
                    var bin = (ObjectNode) risk.withArray("calibrationBins").get(0);
                    bin.put("count", 1);
                    bin.put("meanProbability", 0.9);
                    bin.put("observedRate", 1.0);
                });
    }

    private void reject(Consumer<ObjectNode> mutation) {
        var result = valid();
        mutation.accept(result);
        assertThatThrownBy(() -> BacktestResultValidator.validate(result, input(), job))
                .isInstanceOf(ForecastException.class);
    }

    private ObjectNode input() {
        var input = json.createObjectNode();
        input.put("snapshotId", job.snapshotId().toString());
        input.put("companyId", job.companyId().toString());
        input.put("accountId", job.accountId().toString());
        input.put("inputDataVersion", 1);
        var rq = input.putObject("request");
        rq.put("scope", "HISTORY_ONLY");
        rq.put("windowWeeks", 12);
        rq.put("firstOrigin", "2026-09-01");
        rq.put("lastOrigin", "2026-09-01");
        rq.put("stepDays", 7);
        rq.put("evaluateRisk", false);
        input.putArray("originSnapshots").addObject().put("asOfDate", "2026-09-01");
        return input;
    }

    private ObjectNode valid() {
        var input = input();
        var result = json.createObjectNode();
        for (String field :
                java.util.List.of("snapshotId", "companyId", "accountId", "inputDataVersion")) {
            result.set(field, input.get(field));
        }
        input.path("request")
                .properties()
                .forEach(entry -> result.set(entry.getKey(), entry.getValue()));
        result.put("id", job.resultId().toString());
        result.put("jobId", job.id().toString());
        result.put("algorithm", "WEEKDAY_MEAN_V1");
        result.put("algorithmVersion", "WEEKDAY_MEAN_V1");
        result.put("status", "COMPLETE");
        result.put("evaluatedOrigins", 1);
        result.put("skippedOrigins", 0);
        result.put("evaluatedDays", 30);
        result.put("maeBalance", "0.00");
        result.put("rmseBalance", "0.00");
        result.putArray("warnings");
        var origin = result.putArray("origins").addObject();
        origin.put("asOfDate", "2026-09-01");
        origin.put("status", "EVALUATED");
        origin.putNull("reason");
        origin.put("maeBalance", "0.00");
        origin.put("evaluatedDays", 30);
        var horizons = result.putArray("horizons");
        for (int i = 1; i <= 30; i++) {
            var metric = horizons.addObject();
            metric.put("horizonDay", i);
            metric.put("evaluatedCount", 1);
            metric.put("maeBalance", "0.00");
            metric.put("rmseBalance", "0.00");
        }
        var gap = result.putObject("gapMetrics");
        gap.put("truePositive", 0);
        gap.put("falsePositive", 0);
        gap.put("falseNegative", 0);
        for (String field :
                java.util.List.of(
                        "precision", "recall", "medianLeadDays", "dateMaeDays", "shortfallMae")) {
            gap.putNull(field);
        }
        var risk = result.putObject("riskMetrics");
        risk.put("evaluatedCount", 0);
        risk.putNull("brierScore");
        risk.putNull("interval80Coverage");
        var bins = risk.putArray("calibrationBins");
        for (int b = 0; b < 5; b++) {
            var bin = bins.addObject();
            bin.put("fromProbability", b / 5.0);
            bin.put("toProbability", (b + 1) / 5.0);
            bin.put("count", 0);
            bin.putNull("meanProbability");
            bin.putNull("observedRate");
        }
        return result;
    }
}
