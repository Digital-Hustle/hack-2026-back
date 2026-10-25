package ru.cashflow.orchestrator.features.assistant.command;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import ru.cashflow.orchestrator.features.alerts.model.GapDetails;
import ru.cashflow.orchestrator.features.analytics.model.AnalyticsTypes.Entry;
import ru.cashflow.orchestrator.features.analytics.model.AnalyticsTypes.Report;
import ru.cashflow.orchestrator.features.analytics.model.AnalyticsTypes.Total;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Context;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Fact;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Metadata;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Source;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Topic;
import ru.cashflow.orchestrator.features.ledger.command.DataQualityCalculator.Summary;
import ru.cashflow.orchestrator.features.ledger.model.CategoryCode;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;

/** Only server-built statements are eligible for selection. Raw statements and snapshots never leave the service. */
@Component
public class AssistantContextBuilder {
    public static final int MAX_GAPS = 8;
    private static final int MAX_FACTS = 240;
    private static final int MAX_WARNINGS = 32;
    private static final int MAX_CATEGORY_GROUPS = 64;
    private static final int MAX_COUNTERPARTY_GROUPS = 20;

    public Evidence start(JsonNode dashboard, JsonNode forecast) {
        boolean available = forecast != null && forecast.isObject();
        boolean stale = available && ("STALE".equals(forecast.path("staleness").asText())
                || forecast.path("inputDataVersion").asLong() < dashboard.path("dataVersion").asLong());
        Evidence evidence = new Evidence(new Metadata(java.time.LocalDate.parse(dashboard.path("asOfDate").asText()),
                dashboard.path("dataVersion").asLong()), available, stale);
        if (stale) {
            evidence.warn("Прогноз устарел; его показатели относятся к сохранённой версии данных.");
        }
        return evidence;
    }

    public void overview(Evidence e, JsonNode dashboard, String base) {
        String source = e.source("DASHBOARD", base + "/dashboard", e.metadata.asOfDate().toString(),
                e.metadata.dataVersion(), null, null, null, null, "CURRENT");
        JsonNode balance = dashboard.path("currentBalance");
        if (balance.isObject()) {
            e.fact(source, "Остаток на " + balance.path("date").asText() + ": "
                    + money(balance.path("balance")) + " RUB.");
        } else {
            e.warn("Текущий остаток неизвестен; неизвестный остаток не равен нулю.");
        }
        for (JsonNode plan : dashboard.path("upcomingPayments")) {
            e.fact(source, "План «" + quote(plan.path("title").asText()) + "», срок "
                    + plan.path("dueDate").asText() + ", " + direction(plan.path("direction").asText())
                    + ": неоплаченная часть " + money(plan.path("remainingAmount")) + " RUB.");
        }
        e.warn("Dashboard показывает не более десяти ближайших планов; это не полный реестр обязательств.");
        int count = 0;
        for (JsonNode alert : dashboard.path("activeAlerts")) {
            if (count++ >= MAX_GAPS) {
                e.warn("Список предупреждений сокращён; остальные доступны в приложении.");
                break;
            }
            e.fact(source, "Предупреждение " + quote(alert.path("code").asText()) + ", период "
                    + alert.path("fromDate").asText() + " — " + alert.path("toDate").asText() + ".");
        }
    }

    public void forecast(Evidence e, JsonNode forecast, String base) {
        if (forecast == null || !forecast.isObject()) {
            e.warn("Сохранённого прогноза нет; прогнозные суммы и вероятность неизвестны.");
            return;
        }
        UUID id = uuid(forecast, "id");
        String asOf = forecast.path("asOfDate").asText();
        var cutoff = java.time.LocalDate.parse(asOf);
        String source = e.source("FORECAST", base + "/forecasts/" + id, asOf,
                forecast.path("inputDataVersion").asLong(), cutoff.plusDays(1).toString(),
                cutoff.plusDays(forecast.path("horizonDays").asInt(30)).toString(), id, uuid(forecast, "snapshotId"),
                forecast.path("staleness").asText());
        e.fact(source, "Сохранённый прогноз на дату " + asOf + ", режим "
                + quote(forecast.path("scope").asText()) + ", алгоритм "
                + quote(forecast.path("algorithmVersion").asText()) + ".");
        e.fact(source, "Минимальный ожидаемый остаток: " + money(forecast.path("expectedMinimumBalance")) + " RUB.");
        e.fact(source, "Максимальный ожидаемый дефицит: " + money(forecast.path("maxExpectedShortfall")) + " RUB.");
        if (!forecast.path("firstExpectedGapDate").isNull() && forecast.has("firstExpectedGapDate")) {
            e.fact(source, "Первая ожидаемая дата кассового разрыва: " + forecast.path("firstExpectedGapDate").asText() + ".");
        }
        JsonNode risk = forecast.path("riskEstimate");
        if ("AVAILABLE".equals(risk.path("availability").asText()) && risk.path("probability").isNumber()) {
            e.fact(source, "Оценка вероятности разрыва: "
                    + risk.path("probability").decimalValue().multiply(BigDecimal.valueOf(100)).toPlainString() + " %.");
            if (!risk.path("probabilityCalibrated").asBoolean()) {
                e.warn("Вероятность не прошла калибровку и не является гарантией.");
            }
        } else {
            e.warn("Вероятность разрыва не рассчитана; null не означает нулевой риск.");
        }
        if ("HISTORY_ONLY".equals(forecast.path("scope").asText())) {
            e.warn("Режим HISTORY_ONLY: известные будущие планы в этот расчёт не включены.");
        }
        if (!"GOOD".equals(forecast.path("quality").asText())) {
            e.warn("Качество входов прогноза ограничено: " + quote(forecast.path("quality").asText()) + ".");
        }
        for (JsonNode warning : forecast.path("warnings")) {
            e.warn("Ограничение сохранённого расчёта: " + quote(warning.path("code").asText()) + ".");
        }
        int count = 0;
        for (JsonNode day : forecast.path("days")) {
            if (count++ >= 30) {
                e.warn("Дневной ряд сокращён до тридцати точек.");
                break;
            }
            e.fact(source, "Прогноз на " + day.path("date").asText() + ": поступления "
                    + money(day.path("inflow")) + " RUB, списания " + money(day.path("outflow"))
                    + " RUB, остаток " + money(day.path("balance")) + " RUB.");
        }
    }

    public void gap(Evidence e, GapDetails gap, String base) {
        String source = e.source("GAP_DETAILS", base + "/forecasts/" + gap.forecastId() + "/gaps/" + gap.gapId(),
                gap.asOfDate().toString(), gap.inputDataVersion(), gap.gap().path("fromDate").asText(),
                gap.gap().path("toDate").asText(), gap.forecastId(), gap.snapshotId(), gap.staleness());
        e.fact(source, "Эпизод риска " + quote(gap.gap().path("kind").asText()) + ": "
                + gap.gap().path("fromDate").asText() + " — " + gap.gap().path("toDate").asText()
                + ": ожидаемая нехватка " + money(gap.gap().path("expectedShortfall")) + " RUB.");
        e.warn("Факторы сохранённого расчёта не доказывают причины дефицита; эффект рекомендаций не рассчитывался.");
        int count = 0;
        List<GapDetails.PaymentFactor> factors = new ArrayList<>(gap.mandatoryOutflows());
        factors.addAll(gap.expectedInflows());
        for (var factor : factors) {
            if (count++ >= 20) {
                e.warn("Факторы разрыва сокращены; полный список доступен в источнике.");
                break;
            }
            e.fact(source, "Фактор расчёта: " + direction(factor.direction()) + " «" + quote(factor.title())
                    + "», " + factor.date() + ", " + new BigDecimal(factor.amount()).toPlainString()
                    + " RUB, тип " + factor.kind() + ".");
        }
        gap.recommendations().forEach(r -> e.fact(source, "Рекомендация приложения: " + r.message()));
        gap.dataLimitations().forEach(l -> e.warn("Ограничение разрыва: " + l.code() + ". " + l.message()));
    }

    /** Project the SAME report used by the public analytics API; only the model's display context is capped. */
    public void summary(Evidence e, Report report, Topic topic, String href) {
        boolean categories = topic == Topic.CATEGORIES;
        String from = report.fromDate().toString();
        String to = report.toDate().toString();
        String source = e.source(categories ? "CATEGORY_ANALYTICS" : "COUNTERPARTY_ANALYTICS", href,
                e.metadata.asOfDate().toString(),
                e.metadata.dataVersion(), from, to, null, null, "CURRENT");
        long paymentCount = report.totals().stream().mapToLong(Total::paymentCount).sum();
        e.fact(source, "По загруженным неаннулированным операциям за " + from + " — " + to
                + ": поступления " + total(report, Direction.IN).toPlainString() + " RUB, списания "
                + total(report, Direction.OUT).toPlainString() + " RUB; операций " + paymentCount + ".");
        int limit = categories ? MAX_CATEGORY_GROUPS : MAX_COUNTERPARTY_GROUPS;
        var groups = report.items().stream().sorted(Comparator.comparing(Entry::amount).reversed()
                .thenComparing(Entry::key, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Entry::direction)).limit(limit).toList();
        for (var group : groups) {
            String label = group.key() == null || group.name() == null ? "Не указан" : group.name();
            if (categories) {
                try {
                    label = CategoryCode.valueOf(group.key() == null ? "" : group.key()).title();
                } catch (IllegalArgumentException ignored) {
                    label = "Неизвестная категория";
                }
            }
            e.fact(source, (categories ? "Категория" : "Контрагент") + " «" + quote(label)
                    + "», " + direction(group.direction().name()) + " за " + from + " — " + to + ": "
                    + group.amount().toPlainString() + " RUB; операций " + group.paymentCount() + ".");
        }
        if (report.items().size() > groups.size()) {
            e.warn("Аналитика сокращена до крупнейших групп; итоговые суммы охватывают все операции периода.");
        }
        if (paymentCount == 0) {
            e.warn("В загруженных данных периода нет операций; это не подтверждает отсутствие реальных платежей.");
        }
    }

    private static BigDecimal total(Report report, Direction direction) {
        return report.totals().stream().filter(t -> t.direction() == direction).map(Total::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void quality(Evidence e, Summary quality, String base, boolean addFacts) {
        if (quality.missingDayCount() > 0) {
            e.warn("История периода неполна: неизвестные дни не считаются нулевыми; суммы отражают загруженные операции.");
        }
        if (quality.balanceMismatchCount() > 0) {
            e.warn("В истории периода есть расхождения остатков.");
        }
        if (addFacts) {
            String source = e.source("DATA_QUALITY", base + "/data-quality?from=" + quality.fromDate()
                    + "&to=" + quality.toDate(), e.metadata.asOfDate().toString(), e.metadata.dataVersion(),
                    quality.fromDate().toString(), quality.toDate().toString(), null, null, "CURRENT");
            e.fact(source, "Качество истории за " + quality.fromDate() + " — " + quality.toDate() + ": "
                    + quality.quality() + ", полных дней " + quality.completeDayCount() + ", неизвестных дней "
                    + quality.missingDayCount() + ", расхождений остатков " + quality.balanceMismatchCount() + ".");
        }
    }

    public void backtest(Evidence e, JsonNode backtest, String base) {
        if (backtest == null || !backtest.isObject()) {
            e.warn("Совместимый backtest отсутствует; точность прогноза не подтверждена.");
            return;
        }
        String source = e.source("BACKTEST", base + "/backtests/" + backtest.path("id").asText(),
                backtest.path("lastOrigin").asText(), backtest.path("inputDataVersion").asLong(),
                backtest.path("firstOrigin").asText(), backtest.path("lastOrigin").asText(), null,
                uuid(backtest, "snapshotId"), backtest.path("staleness").asText());
        e.fact(source, "Backtest за " + backtest.path("firstOrigin").asText() + " — " + backtest.path("lastOrigin").asText()
                + ": MAE остатка " + money(backtest.path("maeBalance")) + " RUB, RMSE "
                + money(backtest.path("rmseBalance")) + " RUB, оценено срезов "
                + backtest.path("evaluatedOrigins").asInt() + ", пропущено " + backtest.path("skippedOrigins").asInt() + ".");
        e.warn("Метрики backtest относятся к историческому эксперименту и не гарантируют будущую точность.");
        if ("STALE".equals(backtest.path("staleness").asText())) {
            e.warn("Backtest относится к предыдущей версии данных; его метрики не пересчитаны на текущей истории.");
        }
        if (!"COMPLETE".equals(backtest.path("status").asText())) {
            e.warn("Backtest выполнен частично; часть срезов или метрик недоступна.");
        }
    }

    private static String money(JsonNode value) {
        return value.isMissingNode() || value.isNull() ? "неизвестно" : new BigDecimal(value.asText()).toPlainString();
    }

    private static String direction(String value) {
        return "IN".equals(value) ? "поступление" : "списание";
    }

    private static UUID uuid(JsonNode node, String field) {
        return node.hasNonNull(field) ? UUID.fromString(node.path(field).asText()) : null;
    }

    private static String quote(String value) {
        String clean = value.replaceAll("[\\p{Cntrl}«»]", " ");
        clean = clean.substring(0, Math.min(clean.length(), 100));
        return clean.replaceAll("([\\\\`*_{}\\[\\]()<>#!|])", "\\\\$1");
    }

    public static final class Evidence {
        private final Metadata metadata;
        private final boolean available;
        private final boolean stale;
        private final List<Source> sources = new ArrayList<>();
        private final List<Fact> facts = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        private Evidence(Metadata metadata, boolean available, boolean stale) {
            this.metadata = metadata;
            this.available = available;
            this.stale = stale;
        }

        private String source(String type, String href, String asOf, long version, String from, String to,
                               UUID forecast, UUID snapshot, String staleness) {
            String id = "s" + (sources.size() + 1);
            sources.add(new Source(id, type, href, asOf, version, from, to, forecast, snapshot, staleness));
            return id;
        }

        private void fact(String source, String text) {
            if (facts.size() < MAX_FACTS) {
                facts.add(new Fact("f" + (facts.size() + 1), source, text));
            } else {
                warn("Контекст сокращён; полные данные доступны по ссылкам на источники.");
            }
        }

        public void warn(String warning) {
            if (!warnings.contains(warning)) {
                if (warnings.size() < MAX_WARNINGS - 1) {
                    warnings.add(warning);
                } else if (warnings.size() < MAX_WARNINGS) {
                    warnings.add("Список ограничений сокращён; проверьте полные данные источников.");
                }
            }
        }

        public Context build() {
            return new Context(metadata, available, stale, List.copyOf(sources), List.copyOf(facts), List.copyOf(warnings));
        }
    }
}
