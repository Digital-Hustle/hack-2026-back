package ru.cashflow.orchestrator.features.alerts.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import ru.cashflow.orchestrator.features.alerts.model.AlertTypes.Code;
import ru.cashflow.orchestrator.features.alerts.model.AlertTypes.Draft;
import ru.cashflow.orchestrator.features.forecast.model.ForecastResult;

/** Uses the accepted result and its saved input only; it does not run a forecast or interpret today's plans. */
public final class ForecastAlertFactory {

    private ForecastAlertFactory() {}

    public static List<Draft> create(ForecastResult result, JsonNode input, JsonNode response) {
        List<Draft> alerts = new ArrayList<>();
        for (var gap : result.gaps()) {
            Code code = Code.valueOf(gap.kind());
            alerts.add(new Draft("gap:" + gap.id(), gap.id(), null, code,
                    code == Code.CASH_GAP ? "Возможная нехватка денег" : "Остаток ниже резерва",
                    "В сохранённом прогнозе есть период нарушения указанного порога. Откройте факторы и ограничения расчёта.",
                    gap.fromDate(), gap.toDate(), gap.expectedShortfall(), gap.probability()));
        }
        for (JsonNode plan : input.path("knownPlans")) {
            if (isOpen(plan) && !LocalDate.parse(plan.path("dueDate").asText()).isAfter(result.asOfDate())) {
                UUID id = UUID.fromString(plan.path("id").asText());
                alerts.add(new Draft("plan:" + id, null, id, Code.OVERDUE_PLAN, "Ожидаемый платёж не исполнен",
                        "В снимке расчёта остался открытый план с прошедшей датой. Проверьте исполнение и срок; дата автоматически не переносилась.",
                        LocalDate.parse(plan.path("dueDate").asText()), null,
                        new BigDecimal(plan.path("remainingAmount").asText()), null));
            }
        }
        boolean warnings = false;
        for (JsonNode warning : response.path("warnings")) {
            warnings |= !"UNMATCHED_PLAN".equals(warning.path("code").asText());
        }
        boolean missing = false;
        for (JsonNode day : input.path("dailyHistory")) {
            missing |= "MISSING".equals(day.path("status").asText()) || day.path("balanceMismatch").asBoolean();
        }
        if (!"GOOD".equals(result.quality()) || !"AVAILABLE".equals(result.risk().availability()) || warnings || missing) {
            alerts.add(new Draft("quality", null, null, Code.DATA_QUALITY, "Ограничения прогноза",
                    "У сохранённого расчёта есть ограничения данных или допущения. Неизвестная вероятность не означает отсутствие риска.",
                    null, null, null, null));
        }
        return List.copyOf(alerts);
    }

    public static boolean isOpen(JsonNode plan) {
        String status = plan.path("status").asText();
        return ("OPEN".equals(status) || "PARTIALLY_SETTLED".equals(status))
                && new BigDecimal(plan.path("remainingAmount").asText("0.00")).signum() > 0;
    }
}
