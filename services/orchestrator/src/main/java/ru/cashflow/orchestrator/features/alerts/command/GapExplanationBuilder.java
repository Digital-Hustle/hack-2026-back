package ru.cashflow.orchestrator.features.alerts.command;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import ru.cashflow.orchestrator.features.alerts.model.GapDetails;
import ru.cashflow.orchestrator.features.alerts.model.GapDetails.Limitation;
import ru.cashflow.orchestrator.features.alerts.model.GapDetails.PaymentFactor;
import ru.cashflow.orchestrator.features.alerts.model.GapDetails.ProjectedFlow;
import ru.cashflow.orchestrator.features.alerts.model.GapDetails.Recommendation;

/** Explains saved inputs, not causal effects. No live ledger queries, new predictions or automatic financial actions. */
public final class GapExplanationBuilder {

    private GapExplanationBuilder() {}

    public static GapDetails build(UUID forecastId, UUID snapshotId, UUID gapId, JsonNode input, JsonNode result,
                                   String staleness, java.time.Instant createdAt) {
        JsonNode gap = null;
        for (JsonNode episode : result.path("gaps")) {
            if (gapId.toString().equals(episode.path("id").asText())) {
                gap = episode.deepCopy();
                break;
            }
        }
        if (gap == null) {
            throw new IllegalStateException("Saved gap is absent from the saved forecast response");
        }
        LocalDate asOf = LocalDate.parse(result.path("asOfDate").asText());
        LocalDate from = LocalDate.parse(gap.path("fromDate").asText());
        LocalDate to = LocalDate.parse(gap.path("toDate").asText());
        LocalDate horizonEnd = asOf.plusDays(30);
        List<PaymentFactor> outflows = new ArrayList<>();
        List<PaymentFactor> inflows = new ArrayList<>();
        List<JsonNode> overdue = new ArrayList<>();
        for (JsonNode plan : input.path("knownPlans")) {
            if (!ForecastAlertFactory.isOpen(plan)) {
                continue;
            }
            LocalDate date = LocalDate.parse(plan.path("dueDate").asText());
            if (!date.isAfter(asOf)) {
                overdue.add(plan.deepCopy());
            } else if (!date.isAfter(horizonEnd)) {
                var factor = new PaymentFactor(UUID.fromString(plan.path("id").asText()), "PLANNED",
                        plan.path("source").asText(), plan.path("title").asText(), plan.path("categoryCode").asText(),
                        date, date, plan.path("direction").asText(), plan.path("remainingAmount").asText(), between(date, from, to));
                addFactor(factor, from, to, outflows, inflows);
            }
        }
        // This only displays declared schedules from the snapshot. It does not change or rerun the numerical forecast.
        for (JsonNode pattern : input.path("recurringPatterns")) {
            if (!"CONFIRMED".equals(pattern.path("status").asText())) {
                continue;
            }
            JsonNode schedule = pattern.path("schedule");
            for (LocalDate nominal = asOf.minusDays(1); !nominal.isAfter(horizonEnd); nominal = nominal.plusDays(1)) {
                boolean due = "WEEKLY".equals(schedule.path("frequency").asText())
                        ? nominal.getDayOfWeek().getValue() == schedule.path("weekday").asInt()
                        : nominal.getDayOfMonth() == Math.min(schedule.path("dayOfMonth").asInt(), nominal.lengthOfMonth());
                if (!due) {
                    continue;
                }
                LocalDate date = nominal;
                if ("FOLLOWING".equals(schedule.path("businessDayRule").asText())) {
                    while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                        date = date.plusDays(1);
                    }
                }
                if (!date.isAfter(asOf) || date.isAfter(horizonEnd) || replaced(pattern, nominal, date, input, asOf, horizonEnd)) {
                    continue;
                }
                var factor = new PaymentFactor(UUID.fromString(pattern.path("id").asText()), "RECURRING",
                        "CONFIRMED_RECURRING", pattern.path("categoryCode").asText(), pattern.path("categoryCode").asText(),
                        date, nominal, pattern.path("direction").asText(), pattern.path("amount").asText(), between(date, from, to));
                addFactor(factor, from, to, outflows, inflows);
            }
        }
        Comparator<PaymentFactor> order = Comparator.comparing(PaymentFactor::date).thenComparing(PaymentFactor::id)
                .thenComparing(PaymentFactor::kind);
        outflows.sort(order);
        inflows.sort(order);
        overdue.sort(Comparator.comparing((JsonNode p) -> p.path("dueDate").asText()).thenComparing(p -> p.path("id").asText()));
        List<ProjectedFlow> projected = new ArrayList<>();
        boolean variableExpenses = false;
        boolean variableInflows = false;
        for (JsonNode day : result.path("days")) {
            LocalDate date = LocalDate.parse(day.path("date").asText());
            if (between(date, from, to)) {
                projected.add(new ProjectedFlow(date, day.path("plannedIn").asText(), day.path("plannedOut").asText(),
                        day.path("recurringIn").asText(), day.path("recurringOut").asText(),
                        day.path("residualIn").asText(), day.path("residualOut").asText()));
                variableExpenses |= new BigDecimal(day.path("residualOut").asText()).signum() > 0;
                variableInflows |= new BigDecimal(day.path("residualIn").asText()).signum() > 0;
            }
        }
        List<Limitation> limitations = limitations(input, result);
        List<Recommendation> recommendations = new ArrayList<>();
        List<UUID> planIds = new ArrayList<>();
        outflows.stream().filter(f -> "PLANNED".equals(f.kind())).map(PaymentFactor::id).forEach(planIds::add);
        overdue.stream().map(p -> UUID.fromString(p.path("id").asText())).forEach(planIds::add);
        if (!planIds.isEmpty()) {
            recommendations.add(new Recommendation("CHECK_PLAN_DATE", "Проверьте дату и исполнение плана",
                    "Сверьте срок и уже выполненные оплаты. Не переносите обязательный платёж без согласования; просмотр предупреждения не устраняет дефицит.",
                    planIds.stream().distinct().sorted().toList()));
        }
        List<UUID> incomePlans = new ArrayList<>();
        inflows.stream().filter(f -> "PLANNED".equals(f.kind())).map(PaymentFactor::id).forEach(incomePlans::add);
        overdue.stream().filter(p -> "IN".equals(p.path("direction").asText()))
                .map(p -> UUID.fromString(p.path("id").asText())).forEach(incomePlans::add);
        if (!incomePlans.isEmpty() || variableInflows) {
            recommendations.add(new Recommendation("CLARIFY_INFLOW", "Уточните ожидаемое поступление",
                    "Подтвердите срок и оставшуюся сумму оплаты у клиента. Поступление после периода разрыва не означает, что деньги будут доступны раньше.",
                    incomePlans.stream().distinct().sorted().toList()));
        }
        if (variableExpenses) {
            recommendations.add(new Recommendation("REVIEW_VARIABLE_EXPENSES", "Проверьте переменные расходы",
                    "Проверьте прогнозируемые расходы и категории. Не уменьшайте обязательные платежи автоматически; эффект возможных изменений здесь не рассчитывался.",
                    List.of()));
        }
        return new GapDetails(forecastId, snapshotId, gapId, asOf, result.path("inputDataVersion").asLong(), staleness,
                createdAt, gap, "Это факторы сохранённого расчёта, а не доказанные причины дефицита. Списания — планы и подтверждённые графики, не проверка юридической обязательности. Рекомендации не гарантируют устранение разрыва.",
                List.copyOf(outflows), List.copyOf(inflows), List.copyOf(overdue), List.copyOf(projected),
                limitations, List.copyOf(recommendations));
    }

    private static void addFactor(PaymentFactor factor, LocalDate from, LocalDate to,
                                  List<PaymentFactor> outflows, List<PaymentFactor> inflows) {
        if ("IN".equals(factor.direction())) {
            inflows.add(factor);
        } else if (between(factor.date(), from, to)) {
            outflows.add(factor);
        }
    }

    private static boolean replaced(JsonNode pattern, LocalDate nominal, LocalDate date, JsonNode input,
                                     LocalDate asOf, LocalDate horizonEnd) {
        for (JsonNode plan : input.path("knownPlans")) {
            if (ForecastAlertFactory.isOpen(plan) && "REPLACES_RECURRING".equals(plan.path("relation").asText())
                    && plan.path("recurringPatternId").asText().equals(pattern.path("id").asText())) {
                LocalDate due = LocalDate.parse(plan.path("dueDate").asText());
                if (due.isAfter(asOf) && !due.isAfter(horizonEnd) && (due.equals(nominal) || due.equals(date))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Limitation> limitations(JsonNode input, JsonNode result) {
        Map<String, Limitation> limits = new LinkedHashMap<>();
        if (!"GOOD".equals(result.path("quality").asText())) {
            add(limits, "FORECAST_QUALITY", "У расчёта ограниченное качество входных данных; проверьте историю и предупреждения.", null);
        }
        for (JsonNode warning : result.path("warnings")) {
            String date = warning.path("date").asText(null);
            add(limits, warning.path("code").asText(), warning.path("message").asText(), date == null ? null : LocalDate.parse(date));
        }
        for (JsonNode day : input.path("dailyHistory")) {
            LocalDate date = LocalDate.parse(day.path("date").asText());
            if ("MISSING".equals(day.path("status").asText())) {
                add(limits, "MISSING_DATA", "Неизвестный день не считается днём с нулевыми потоками.", date);
            } else if (day.path("balanceEod").isNull()) {
                add(limits, "MISSING_BALANCE", "Остаток на конец дня неизвестен.", date);
            }
            if (day.path("balanceMismatch").asBoolean()) {
                add(limits, "BALANCE_MISMATCH", "В сохранённой истории остаток не согласован с потоками.", date);
            }
        }
        if (!"AVAILABLE".equals(result.path("riskEstimate").path("availability").asText())) {
            add(limits, "RISK_UNAVAILABLE", "Вероятность разрыва неизвестна: null не означает нулевой риск.", null);
        } else if (!result.path("riskEstimate").path("probabilityCalibrated").asBoolean()) {
            add(limits, "UNCALIBRATED_PROBABILITY", "Оценка вероятности не прошла калибровку; она не является гарантией.", null);
        }
        if ("HISTORY_ONLY".equals(input.path("scope").asText())) {
            add(limits, "PLANS_NOT_INCLUDED", "Этот расчёт не включает известные будущие планы.", null);
        }
        if (!input.path("recurringPatterns").isEmpty()) {
            add(limits, "SIMPLIFIED_CALENDAR", "Графики используют календарь понедельник–пятница; официальные переносы праздников этой версией не учитываются.", null);
        }
        return List.copyOf(limits.values());
    }

    private static void add(Map<String, Limitation> limits, String code, String message, LocalDate date) {
        limits.putIfAbsent(code + ":" + date, new Limitation(code, message, date));
    }

    private static boolean between(LocalDate date, LocalDate from, LocalDate to) {
        return !date.isBefore(from) && !date.isAfter(to);
    }
}
