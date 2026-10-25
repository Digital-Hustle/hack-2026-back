package ru.cashflow.orchestrator.features.assistant.application;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.alerts.application.GapDetailsService;
import ru.cashflow.orchestrator.features.analytics.application.AnalyticsService;
import ru.cashflow.orchestrator.features.assistant.command.AssistantContextBuilder;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Context;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Period;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Plan;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Question;
import ru.cashflow.orchestrator.features.assistant.model.AssistantTypes.Topic;
import ru.cashflow.orchestrator.features.ledger.application.LedgerService;
import ru.cashflow.orchestrator.features.results.application.ResultQueryService;

/** Reads one MVCC snapshot through authorized application APIs, then returns before any external request. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AssistantReadService {
    public static final String CLARIFY_PERIOD = "Укажите обе даты периода в формате YYYY-MM-DD.";
    private static final Pattern ISO_DATE = Pattern.compile("(?<![0-9])\\d{4}-\\d{2}-\\d{2}(?![0-9])");
    private final ResultQueryService results;
    private final GapDetailsService gaps;
    private final LedgerService ledger;
    private final AnalyticsService analytics;
    private final AssistantContextBuilder builder;

    public AssistantReadService(ResultQueryService results, GapDetailsService gaps, LedgerService ledger,
                                  AnalyticsService analytics, AssistantContextBuilder builder) {
        this.results = results;
        this.gaps = gaps;
        this.ledger = ledger;
        this.analytics = analytics;
        this.builder = builder;
    }

    public Context authorize(UUID user, UUID company, UUID account, Question question) {
        JsonNode dashboard = results.dashboard(user, company, account);
        JsonNode forecast = selectedForecast(user, company, account, question, dashboard);
        if (question.gapId() != null) {
            gaps.get(user, company, account, question.forecastId(), question.gapId());
        }
        return builder.start(dashboard, forecast).build();
    }

    public Context load(UUID user, UUID company, UUID account, Question question, Plan plan) {
        JsonNode dashboard = results.dashboard(user, company, account);
        JsonNode forecast = selectedForecast(user, company, account, question, dashboard);
        var e = builder.start(dashboard, forecast);
        String base = "/api/v1/orchestrator/companies/" + company + "/accounts/" + account;
        boolean hasAnalytics = plan.topics().contains(Topic.CATEGORIES) || plan.topics().contains(Topic.COUNTERPARTIES);
        boolean quality = plan.topics().contains(Topic.QUALITY);
        Range range = null;
        if (hasAnalytics || quality) {
            range = range(question, plan.period(), LocalDate.parse(dashboard.path("asOfDate").asText()));
            if (range == null) {
                e.warn(CLARIFY_PERIOD);
                return e.build();
            }
            e.warn("Период истории: " + range.from() + " — " + range.to() + ".");
            builder.quality(e, ledger.dataQuality(user, company, account, range.from(), range.to()), base, quality);
        }
        if (plan.topics().contains(Topic.OVERVIEW)) {
            builder.overview(e, dashboard, base);
        }
        if (plan.topics().contains(Topic.FORECAST) || plan.topics().contains(Topic.GAP_DETAILS)) {
            builder.forecast(e, forecast, base);
        }
        if (plan.topics().contains(Topic.GAP_DETAILS) && forecast.isObject()) {
            UUID forecastId = UUID.fromString(forecast.path("id").asText());
            if (question.gapId() != null) {
                builder.gap(e, gaps.get(user, company, account, forecastId, question.gapId()), base);
            } else {
                int count = 0;
                for (JsonNode gap : forecast.path("gaps")) {
                    if (count++ >= AssistantContextBuilder.MAX_GAPS) {
                        e.warn("Детали разрывов сокращены; выберите конкретные forecastId и gapId.");
                        break;
                    }
                    builder.gap(e, gaps.get(user, company, account, forecastId,
                            UUID.fromString(gap.path("id").asText())), base);
                }
                if (count == 0) {
                    e.warn("В сохранённом расчёте нет эпизодов разрыва для детализации.");
                }
            }
        }
        for (Topic topic : plan.topics()) {
            if (topic == Topic.CATEGORIES || topic == Topic.COUNTERPARTIES) {
                boolean categories = topic == Topic.CATEGORIES;
                String href = "/api/v1/orchestrator/companies/" + company + "/analytics/"
                        + (categories ? "categories" : "counterparties") + "?accountId=" + account
                        + "&fromDate=" + range.from() + "&toDate=" + range.to();
                var report = categories
                        ? analytics.categories(user, company, account, range.from(), range.to(), null)
                        : analytics.counterparties(user, company, account, range.from(), range.to(), null);
                builder.summary(e, report, topic, href);
            }
        }
        if (quality) {
            JsonNode backtest = dashboard.path("latestBacktest");
            if (!compatible(forecast, backtest)) {
                backtest = null;
            }
            builder.backtest(e, backtest, base);
        }
        return e.build();
    }

    private JsonNode selectedForecast(UUID user, UUID company, UUID account, Question question, JsonNode dashboard) {
        return question.forecastId() == null ? dashboard.path("latestForecast")
                : results.get(user, company, account, question.forecastId(), false);
    }

    private static boolean compatible(JsonNode forecast, JsonNode backtest) {
        return forecast.isObject() && backtest.isObject()
                && forecast.path("algorithmVersion").equals(backtest.path("algorithmVersion"))
                && forecast.path("scope").equals(backtest.path("scope"))
                && forecast.path("settings").path("windowWeeks").equals(backtest.path("windowWeeks"));
    }

    /** Dates come from request text/fields or a server-computed calendar, never arbitrary model output. */
    private static Range range(Question question, Period period, LocalDate lastClosed) {
        LocalDate from = question.fromDate();
        LocalDate to = question.toDate();
        if (from == null) {
            var matcher = ISO_DATE.matcher(question.message());
            List<LocalDate> dates = new ArrayList<>();
            try {
                while (matcher.find()) {
                    dates.add(LocalDate.parse(matcher.group()));
                }
            } catch (DateTimeParseException exception) {
                return null;
            }
            if (!dates.isEmpty() && dates.size() != 2) {
                return null;
            }
            if (dates.size() == 2) {
                from = dates.getFirst();
                to = dates.getLast();
            } else {
                LocalDate today = lastClosed.plusDays(1);
                to = lastClosed;
                from = switch (period) {
                    case LAST_7_DAYS -> to.minusDays(6);
                    case LAST_30_DAYS -> to.minusDays(29);
                    case CURRENT_MONTH -> today.withDayOfMonth(1);
                    case PREVIOUS_MONTH -> today.withDayOfMonth(1).minusMonths(1);
                    case EXPLICIT -> null;
                };
                if (period == Period.PREVIOUS_MONTH) {
                    to = today.withDayOfMonth(1).minusDays(1);
                }
            }
        }
        if (from == null || from.isAfter(to)) {
            return null;
        }
        if (to.isAfter(lastClosed) || ChronoUnit.DAYS.between(from, to) >= 366) {
            throw ApiException.unprocessable("AI_INVALID_PERIOD", "Только закрытые дни, не более 366 дней");
        }
        return new Range(from, to);
    }

    private record Range(LocalDate from, LocalDate to) {
    }
}
