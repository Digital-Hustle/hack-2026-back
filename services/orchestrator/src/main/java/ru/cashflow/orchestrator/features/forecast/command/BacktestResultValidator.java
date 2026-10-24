package ru.cashflow.orchestrator.features.forecast.command;

import com.fasterxml.jackson.databind.JsonNode;

import ru.cashflow.orchestrator.features.forecast.gateway.ForecastException;
import ru.cashflow.orchestrator.features.jobs.model.JobTypes.Job;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** Validates identities, fixed experiment, grid and metric consistency before persistence. */
public final class BacktestResultValidator {
    private BacktestResultValidator() {}

    public static void validate(JsonNode result, JsonNode input, Job job) {
        require(result != null && result.isObject());
        require(
                job.resultId().toString().equals(text(result, "id"))
                        && job.id().toString().equals(text(result, "jobId")));
        for (String field : Set.of("snapshotId", "companyId", "accountId", "inputDataVersion")) {
            require(result.path(field).equals(input.path(field)));
        }
        var request = input.path("request");
        for (String field :
                Set.of("scope", "windowWeeks", "firstOrigin", "lastOrigin", "stepDays")) {
            require(result.path(field).equals(request.path(field)));
        }
        require(
                "WEEKDAY_MEAN_V1".equals(text(result, "algorithm"))
                        && "WEEKDAY_MEAN_V1".equals(text(result, "algorithmVersion")));
        require(Set.of("COMPLETE", "PARTIAL").contains(text(result, "status")));
        var first = LocalDate.parse(request.path("firstOrigin").asText());
        var last = LocalDate.parse(request.path("lastOrigin").asText());
        int step = request.path("stepDays").asInt();
        Set<String> supplied = new HashSet<>();
        input.path("originSnapshots")
                .forEach(origin -> supplied.add(origin.path("asOfDate").asText()));
        var origins = result.path("origins");
        require(origins.isArray());
        int evaluated = 0;
        int skipped = 0;
        int days = 0;
        int index = 0;
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(step)) {
            require(index < origins.size());
            var origin = origins.get(index++);
            require(date.toString().equals(text(origin, "asOfDate")));
            int count = integer(origin, "evaluatedDays");
            if ("EVALUATED".equals(text(origin, "status"))) {
                require(
                        supplied.contains(date.toString())
                                && count > 0
                                && count <= 30
                                && origin.has("reason")
                                && origin.path("reason").isNull());
                money(origin, "maeBalance", false);
                evaluated++;
                days += count;
            } else {
                require(
                        "SKIPPED".equals(text(origin, "status"))
                                && count == 0
                                && !text(origin, "reason").isBlank());
                require(origin.has("maeBalance") && origin.path("maeBalance").isNull());
                skipped++;
            }
        }
        require(
                index == origins.size()
                        && evaluated > 0
                        && evaluated == integer(result, "evaluatedOrigins")
                        && skipped == integer(result, "skippedOrigins")
                        && days == integer(result, "evaluatedDays"));
        var mae = money(result, "maeBalance", false);
        var rmse = money(result, "rmseBalance", false);
        require(rmse.compareTo(mae) >= 0);
        require(result.path("horizons").isArray() && result.path("horizons").size() == 30);
        int total = 0;
        for (int h = 0; h < 30; h++) {
            var metric = result.path("horizons").get(h);
            require(integer(metric, "horizonDay") == h + 1);
            int count = integer(metric, "evaluatedCount");
            require(count >= 0 && count <= evaluated);
            total += count;
            var hm = money(metric, "maeBalance", count == 0);
            var hr = money(metric, "rmseBalance", count == 0);
            require(
                    count == 0
                            ? hm == null && hr == null
                            : hm != null && hr != null && hr.compareTo(hm) >= 0);
        }
        require(total == days && result.path("warnings").isArray());
        var gap = result.path("gapMetrics");
        int tp = integer(gap, "truePositive");
        int fp = integer(gap, "falsePositive");
        int fn = integer(gap, "falseNegative");
        require(tp >= 0 && fp >= 0 && fn >= 0);
        ratio(gap, "precision", tp, tp + fp);
        ratio(gap, "recall", tp, tp + fn);
        for (String field : Set.of("medianLeadDays", "dateMaeDays")) {
            var value = nullableNumber(gap, field, false);
            require(tp == 0 ? value == null : value != null);
            if (field.equals("medianLeadDays") && value != null) {
                require(
                        value.compareTo(BigDecimal.ONE) >= 0
                                && value.compareTo(BigDecimal.valueOf(30)) <= 0);
            }
        }
        var shortfall = money(gap, "shortfallMae", tp == 0);
        require(tp == 0 ? shortfall == null : shortfall != null);
        var risk = result.path("riskMetrics");
        int riskDays = integer(risk, "evaluatedCount");
        require(riskDays >= 0 && riskDays <= days);
        var brier = nullableNumber(risk, "brierScore", true);
        var coverage = nullableNumber(risk, "interval80Coverage", true);
        require(riskDays == 0 ? brier == null : brier != null && coverage != null);
        require(risk.path("calibrationBins").isArray() && risk.path("calibrationBins").size() == 5);
        int binTotal = 0;
        for (int b = 0; b < 5; b++) {
            var bin = risk.path("calibrationBins").get(b);
            int count = integer(bin, "count");
            require(count >= 0);
            binTotal += count;
            require(
                    nullableNumber(bin, "fromProbability", true)
                                    .compareTo(BigDecimal.valueOf(b).divide(BigDecimal.valueOf(5)))
                            == 0);
            require(
                    nullableNumber(bin, "toProbability", true)
                                    .compareTo(
                                            BigDecimal.valueOf(b + 1).divide(BigDecimal.valueOf(5)))
                            == 0);
            var p = nullableNumber(bin, "meanProbability", true);
            var observed = nullableNumber(bin, "observedRate", true);
            require(count == 0 ? p == null && observed == null : p != null && observed != null);
            if (p != null) {
                require(p.compareTo(BigDecimal.valueOf(b).divide(BigDecimal.valueOf(5))) >= 0);
                int upperComparison =
                        p.compareTo(BigDecimal.valueOf(b + 1).divide(BigDecimal.valueOf(5)));
                require(b == 4 ? upperComparison <= 0 : upperComparison < 0);
            }
        }
        require(binTotal == riskDays);
        if ("COMPLETE".equals(result.path("status").asText())) {
            require(
                    skipped == 0
                            && days == evaluated * 30
                            && (!request.path("evaluateRisk").asBoolean() || riskDays == days));
        }
    }

    private static void ratio(JsonNode node, String field, int numerator, int denominator) {
        var value = nullableNumber(node, field, true);
        require(
                denominator == 0
                        ? value == null
                        : value != null
                                && value.subtract(
                                                        BigDecimal.valueOf(numerator)
                                                                .divide(
                                                                        BigDecimal.valueOf(
                                                                                denominator),
                                                                        java.math.MathContext
                                                                                .DECIMAL128))
                                                .abs()
                                                .compareTo(new BigDecimal("0.000000001"))
                                        <= 0);
    }

    private static BigDecimal nullableNumber(JsonNode node, String field, boolean probability) {
        require(node.has(field));
        var value = node.path(field);
        if (value.isNull()) {
            return null;
        }
        require(value.isNumber());
        var number = value.decimalValue();
        require(number.signum() >= 0 && (!probability || number.compareTo(BigDecimal.ONE) <= 0));
        return number;
    }

    private static BigDecimal money(JsonNode node, String field, boolean nullable) {
        require(node.has(field));
        if (nullable && node.path(field).isNull()) {
            return null;
        }
        String value = text(node, field);
        require(value.matches("(0|[1-9][0-9]{0,16})\\.[0-9]{2}"));
        return new BigDecimal(value);
    }

    private static int integer(JsonNode node, String field) {
        require(node.path(field).isIntegralNumber() && node.path(field).canConvertToInt());
        return node.path(field).asInt();
    }

    private static String text(JsonNode node, String field) {
        require(node.path(field).isTextual());
        return node.path(field).asText();
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new ForecastException(
                    ForecastException.Kind.PERMANENT,
                    "INVALID_BACKTEST_RESULT",
                    "Ответ backtest нарушает контракт или инварианты",
                    null);
        }
    }
}
