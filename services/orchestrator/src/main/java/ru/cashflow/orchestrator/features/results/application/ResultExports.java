package ru.cashflow.orchestrator.features.results.application;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Renders stored Forecast/Backtest responses as downloadable files: the step-by-step calculation trace, CSV with the
 * daily series behind the charts, and a Markdown quality report. Pure functions over the saved JSON; nothing is
 * recomputed, so a download always matches the persisted result.
 */
public final class ResultExports {
    private static final String NA = "n/a";

    private ResultExports() {}

    public static String traceText(JsonNode result, String kind) {
        var out = new StringBuilder();
        out.append("# ").append(kind).append(" calculation trace\n");
        out.append("id: ").append(text(result, "id")).append('\n');
        out.append("algorithm: ").append(text(result, "algorithmVersion")).append('\n');
        if (result.has("asOfDate")) {
            out.append("asOfDate: ").append(text(result, "asOfDate")).append('\n');
        }
        out.append("inputDataVersion: ").append(text(result, "inputDataVersion")).append('\n');
        out.append("snapshotId: ").append(text(result, "snapshotId")).append('\n');
        out.append("createdAt: ").append(text(result, "createdAt")).append("\n\n");
        var trace = result.path("trace");
        if (!trace.isArray() || trace.size() == 0) {
            out.append("Trace is not available for this result (calculated before tracing was introduced). ")
                    .append("Run the calculation again to get the step log.\n");
            return out.toString();
        }
        for (JsonNode step : trace) {
            out.append(String.format("[%03d] %-18s %s%n", step.path("seq").asInt(), step.path("stage").asText(),
                    step.path("message").asText()));
            for (Map.Entry<String, JsonNode> entry : step.path("data").properties()) {
                out.append("        ").append(entry.getKey()).append(" = ").append(entry.getValue().asText()).append('\n');
            }
        }
        return out.toString();
    }

    public static String forecastCsv(JsonNode result) {
        var out = new StringBuilder();
        out.append("date,plannedIn,plannedOut,recurringIn,recurringOut,residualIn,residualOut,inflow,outflow,balance,"
                + "balanceP10,balanceP50,balanceP90,pBelowZero,pBelowBuffer\n");
        for (JsonNode day : result.path("days")) {
            row(out, day, List.of("date", "plannedIn", "plannedOut", "recurringIn", "recurringOut", "residualIn",
                    "residualOut", "inflow", "outflow", "balance", "balanceP10", "balanceP50", "balanceP90",
                    "pBelowZero", "pBelowBuffer"));
        }
        return out.toString();
    }

    public static String backtestCsv(JsonNode result) {
        var out = new StringBuilder();
        out.append("origin,status,date,horizonDay,forecastBalance,actualBalance,error,forecastInflow,forecastOutflow,"
                + "actualInflow,actualOutflow,balanceP10,balanceP90,pBelowZero,predictedBreach,actualBreach\n");
        for (JsonNode origin : result.path("origins")) {
            var days = origin.path("days");
            if (!days.isArray() || days.size() == 0) {
                out.append(csv(text(origin, "asOfDate"))).append(',').append(csv(text(origin, "status")))
                        .append(",,,,,,,,,,,,,,\n");
                continue;
            }
            for (JsonNode day : days) {
                out.append(csv(text(origin, "asOfDate"))).append(',').append(csv(text(origin, "status"))).append(',');
                out.append(csv(text(day, "date"))).append(',').append(csv(text(day, "horizonDay"))).append(',');
                out.append(csv(text(day, "forecastBalance"))).append(',').append(csv(text(day, "actualBalance")))
                        .append(',').append(csv(error(day))).append(',');
                row(out, day, List.of("forecastInflow", "forecastOutflow", "actualInflow", "actualOutflow",
                        "balanceP10", "balanceP90", "pBelowZero", "predictedBreach", "actualBreach"));
            }
        }
        return out.toString();
    }

    /** Markdown report for the jury/team: metrics, confusion matrices, reference models and the model choice. */
    public static String backtestReport(JsonNode result) {
        var out = new StringBuilder();
        out.append("# Отчёт о качестве прогноза (backtest)\n\n");
        out.append("| Параметр | Значение |\n|---|---|\n");
        for (String field : List.of("id", "algorithmVersion", "scope", "windowWeeks", "firstOrigin", "lastOrigin",
                "stepDays", "status", "evaluatedOrigins", "skippedOrigins", "evaluatedDays", "createdAt")) {
            out.append("| ").append(field).append(" | ").append(text(result, field)).append(" |\n");
        }
        out.append("\nМетод: rolling-origin time-series cross-validation. На каждой дате отсечки T модель заново ")
                .append("обучается только на данных, известных до T, прогнозирует T+1…T+30 и сравнивается с фактом.\n\n");
        var quality = result.path("quality");
        if (quality.isMissingNode() || quality.isNull()) {
            out.append("Расширенные метрики отсутствуют (результат рассчитан до их появления). MAE=")
                    .append(text(result, "maeBalance")).append(", RMSE=").append(text(result, "rmseBalance"))
                    .append('\n');
            return out.toString();
        }
        out.append("## Ошибка прогноза остатка (₽)\n\n");
        out.append("| Модель | Дней | MAE | MSE | RMSE | WAPE | Skill vs naive | MAE входящих | MAE исходящих |\n");
        out.append("|---|---|---|---|---|---|---|---|---|\n");
        score(out, quality.path("primary"), true);
        for (JsonNode benchmark : quality.path("benchmarks")) {
            score(out, benchmark, false);
        }
        out.append("\nSkill vs naive = 1 − MAE/MAE(остаток не меняется). > 0 — модель лучше наивной.\n\n");
        out.append("## Ошибка по горизонту\n\n| День | MAE | RMSE | Наблюдений |\n|---|---|---|---|\n");
        for (JsonNode horizon : result.path("horizons")) {
            int day = horizon.path("horizonDay").asInt();
            if (day == 1 || day == 7 || day == 14 || day == 21 || day == 30) {
                out.append("| ").append(day).append(" | ").append(text(horizon, "maeBalance")).append(" | ")
                        .append(text(horizon, "rmseBalance")).append(" | ").append(text(horizon, "evaluatedCount"))
                        .append(" |\n");
            }
        }
        out.append("\n## Матрица ошибок: кассовый разрыв по дням (остаток < 0)\n\n");
        matrix(out, quality.path("primary").path("dailyMatrix"));
        out.append("\n## Матрица ошибок: «будет ли разрыв в ближайшие 30 дней» по датам отсечки\n\n");
        matrix(out, quality.path("originMatrix"));
        if (!quality.path("bufferDailyMatrix").isNull() && !quality.path("bufferDailyMatrix").isMissingNode()) {
            out.append("\n## Матрица ошибок: остаток ниже буфера ").append(text(quality.path("bufferDailyMatrix"), "threshold"))
                    .append("\n\n");
            matrix(out, quality.path("bufferDailyMatrix"));
        }
        if (!quality.path("scenarioDailyMatrix").isNull() && !quality.path("scenarioDailyMatrix").isMissingNode()) {
            out.append("\n## Матрица ошибок по вероятности сценариев (p ≥ ").append(text(quality, "scenarioThreshold"))
                    .append(")\n\n");
            matrix(out, quality.path("scenarioDailyMatrix"));
        }
        var gaps = result.path("gapMetrics");
        out.append("\n## Эпизоды разрывов (сопоставление один к одному по пересечению дней)\n\n");
        out.append("| TP | FP | FN | Precision | Recall | F1 | Медиана заблаговременности, дн | Ошибка даты, дн | Ошибка суммы, ₽ |\n");
        out.append("|---|---|---|---|---|---|---|---|---|\n| ");
        for (String field : List.of("truePositive", "falsePositive", "falseNegative", "precision", "recall", "f1",
                "medianLeadDays", "dateMaeDays", "shortfallMae")) {
            out.append(number(gaps.path(field))).append(" | ");
        }
        out.append("\n\nTN для эпизодов не определён по построению; полная матрица — по дням выше.\n");
        var risk = result.path("riskMetrics");
        out.append("\n## Вероятности (сценарный риск)\n\n");
        out.append("Brier score: ").append(number(risk.path("brierScore"))).append(", покрытие интервала P10–P90: ")
                .append(number(risk.path("interval80Coverage"))).append(", дней: ").append(text(risk, "evaluatedCount"))
                .append("\n\n| Бин вероятности | Дней | Средняя прогнозная p | Фактическая частота |\n|---|---|---|---|\n");
        for (JsonNode bin : risk.path("calibrationBins")) {
            out.append("| ").append(text(bin, "fromProbability")).append("–").append(text(bin, "toProbability"))
                    .append(" | ").append(text(bin, "count")).append(" | ").append(number(bin.path("meanProbability")))
                    .append(" | ").append(number(bin.path("observedRate"))).append(" |\n");
        }
        var selection = quality.path("selection");
        if (!selection.isNull() && !selection.isMissingNode()) {
            out.append("\n## Выбор модели и окна (честная проверка)\n\n");
            out.append("Кандидат выбирается по MAE на ранних ").append(text(selection, "selectionOrigins"))
                    .append(" отсечках, затем оценивается на поздних ").append(text(selection, "holdoutOrigins"))
                    .append(" (с ").append(text(selection, "holdoutFrom")).append("), которые в выборе не участвовали.\n\n");
            out.append("| Кандидат | MAE (выбор) | MAE (holdout) |\n|---|---|---|\n");
            for (JsonNode candidate : selection.path("candidates")) {
                out.append("| ").append(text(candidate, "model")).append(" | ").append(text(candidate, "selectionMae"))
                        .append(" | ").append(text(candidate, "holdoutMae")).append(" |\n");
            }
            out.append("\nВыбрана: **").append(text(selection, "selectedModel")).append("**, MAE на holdout ")
                    .append(text(selection, "selectedHoldoutMae")).append(" против ")
                    .append(text(selection, "primaryHoldoutMae")).append(" у основной модели.\n");
        }
        out.append("\n## Примечания\n\n");
        for (JsonNode note : quality.path("notes")) {
            out.append("- ").append(note.asText()).append('\n');
        }
        for (JsonNode warning : result.path("warnings")) {
            out.append("- ").append(text(warning, "code")).append(": ").append(text(warning, "message")).append('\n');
        }
        return out.toString();
    }

    private static void score(StringBuilder out, JsonNode score, boolean primary) {
        out.append("| ").append(primary ? "**" + text(score, "model") + "** (основная)" : text(score, "model"))
                .append(" | ").append(text(score, "evaluatedDays"));
        for (String field : List.of("maeBalance", "mseBalance", "rmseBalance", "wapeBalance", "skillScoreVsNaive",
                "inflowMae", "outflowMae")) {
            out.append(" | ").append(number(score.path(field)));
        }
        out.append(" |\n");
    }

    private static void matrix(StringBuilder out, JsonNode matrix) {
        if (matrix.isMissingNode() || matrix.isNull()) {
            out.append("Нет данных.\n");
            return;
        }
        out.append("| | Факт: разрыв | Факт: нет разрыва |\n|---|---|---|\n");
        out.append("| **Прогноз: разрыв** | TP = ").append(text(matrix, "truePositive")).append(" | FP = ")
                .append(text(matrix, "falsePositive")).append(" |\n");
        out.append("| **Прогноз: нет разрыва** | FN = ").append(text(matrix, "falseNegative")).append(" | TN = ")
                .append(text(matrix, "trueNegative")).append(" |\n\n");
        out.append("Accuracy ").append(number(matrix.path("accuracy"))).append(" (наивная «всегда большинство» ")
                .append(number(matrix.path("majorityBaselineAccuracy"))).append("), Precision ")
                .append(number(matrix.path("precision"))).append(", Recall ").append(number(matrix.path("recall")))
                .append(", Specificity ").append(number(matrix.path("specificity"))).append(", F1 ")
                .append(number(matrix.path("f1"))).append(", Balanced accuracy ")
                .append(number(matrix.path("balancedAccuracy"))).append(", доля разрывов ")
                .append(number(matrix.path("prevalence"))).append(".\n");
    }

    private static String error(JsonNode day) {
        var forecast = day.path("forecastBalance");
        var actual = day.path("actualBalance");
        if (!forecast.isTextual() || !actual.isTextual()) {
            return "";
        }
        return new java.math.BigDecimal(forecast.asText()).subtract(new java.math.BigDecimal(actual.asText()))
                .toPlainString();
    }

    private static void row(StringBuilder out, JsonNode node, List<String> fields) {
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            var value = node.path(fields.get(index));
            out.append(value.isMissingNode() || value.isNull() ? "" : csv(value.asText()));
        }
        out.append('\n');
    }

    private static String csv(String value) {
        if (value == null || value.equals(NA)) {
            return "";
        }
        return value.contains(",") || value.contains("\"") || value.contains("\n")
                ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static String number(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return NA;
        }
        if (value.isNumber() && !value.isIntegralNumber()) {
            return String.format(java.util.Locale.ROOT, "%.4f", value.asDouble());
        }
        return value.asText();
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? NA : value.asText();
    }
}
