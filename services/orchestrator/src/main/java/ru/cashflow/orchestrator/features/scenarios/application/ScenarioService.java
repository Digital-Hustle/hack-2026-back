package ru.cashflow.orchestrator.features.scenarios.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyAccess;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;
import ru.cashflow.orchestrator.features.plans.repository.PlanRepository;
import ru.cashflow.orchestrator.features.results.application.ResultQueryService;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.Base;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.BaseDay;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.Change;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.CostTarget;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.Effect;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.ScenarioDay;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.Summary;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.Type;

/** Stateless "what if" preview over the stored forecast. Read access is enough: nothing is written. */
@Service
@Transactional(readOnly = true)
public class ScenarioService {
    private static final BigDecimal MAX_DROP = new BigDecimal("100");
    private static final BigDecimal MAX_GROWTH = new BigDecimal("300");
    private static final int MAX_DELAY_DAYS = 90;

    /** Unresolved lever as the caller sent it; plan references are resolved here. */
    public record ChangeSpec(Type type, String title, UUID planId, BigDecimal amount, LocalDate date,
                             Integer delayDays, BigDecimal percent, LocalDate fromDate, CostTarget target) {
    }

    public record BaseInfo(UUID id, LocalDate asOfDate, String scope, String staleness, String createdAt,
                           BigDecimal startBalance, BigDecimal buffer) {
    }

    /**
     * newGap: the scenario creates a cash gap the base forecast does not have; gapEarlier: both have a gap and the
     * scenario one starts earlier; additionalShortfall: how much deeper the deepest point gets.
     */
    public record Comparison(Summary base, Summary scenario, boolean newGap, boolean gapEarlier,
                             BigDecimal additionalShortfall, Integer gapShiftDays) {
    }

    public record Preview(BaseInfo baseForecast, Comparison comparison, List<ScenarioDay> days, List<Effect> changes,
                          List<String> notes) {
    }

    private final CompanyAccess access;
    private final ResultQueryService results;
    private final PlanRepository plans;

    public ScenarioService(CompanyAccess access, ResultQueryService results, PlanRepository plans) {
        this.access = access;
        this.results = results;
        this.plans = plans;
    }

    public Preview preview(UUID userId, UUID companyId, UUID accountId, UUID forecastId, List<ChangeSpec> specs) {
        var scope = access.requireRead(userId, companyId);
        JsonNode forecast = baseForecast(userId, companyId, accountId, forecastId);
        Base base = toBase(forecast);
        List<String> notes = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        for (ChangeSpec c : specs) {
            changes.add(resolve(c, base, forecast, scope, accountId));
        }
        var result = ScenarioEngine.run(base, changes);
        var baseSummary = result.base();
        var scenarioSummary = result.scenario();
        boolean newGap = scenarioSummary.firstGapDate() != null && baseSummary.firstGapDate() == null;
        boolean earlier = scenarioSummary.firstGapDate() != null && baseSummary.firstGapDate() != null
                && scenarioSummary.firstGapDate().isBefore(baseSummary.firstGapDate());
        Integer shift = scenarioSummary.firstGapDate() != null && baseSummary.firstGapDate() != null
                ? (int) ChronoUnit.DAYS.between(baseSummary.firstGapDate(), scenarioSummary.firstGapDate()) : null;
        BigDecimal additional = scenarioSummary.maxShortfall().subtract(baseSummary.maxShortfall());
        if ("STALE".equals(forecast.path("staleness").asText())) {
            notes.add("Базовый прогноз устарел: после его построения изменились данные. Обновите прогноз, чтобы "
                    + "сценарий считался по актуальным данным.");
        }
        notes.add("Сценарий пересчитывает дневные потоки базового прогноза; вероятность разрыва (pBelowZero) "
                + "не пересчитывается, диапазоны P10/P50/P90 сдвинуты на ту же величину.");
        if (scenarioSummary.firstGapDate() == null) {
            notes.add("В выбранном сценарии кассового разрыва на горизонте прогноза нет.");
        }
        return new Preview(
                new BaseInfo(UUID.fromString(forecast.path("id").asText()), base.asOfDate(),
                        forecast.path("scope").asText(), forecast.path("staleness").asText(),
                        forecast.path("createdAt").asText(null), base.startBalance(), base.buffer()),
                new Comparison(baseSummary, scenarioSummary, newGap, earlier, additional, shift), result.days(),
                result.effects(), notes);
    }

    private JsonNode baseForecast(UUID userId, UUID companyId, UUID accountId, UUID forecastId) {
        if (forecastId != null) {
            return results.get(userId, companyId, accountId, forecastId, false);
        }
        return results.list(userId, companyId, accountId, false, 1, 0).stream().findFirst().orElseThrow(
                () -> ApiException.unprocessable("NO_FORECAST", "Сначала постройте прогноз: сценарий считается "
                        + "поверх него"));
    }

    private static Base toBase(JsonNode forecast) {
        List<BaseDay> days = new ArrayList<>();
        for (JsonNode d : forecast.path("days")) {
            BigDecimal recurringOut = dec(d, "recurringOut");
            BigDecimal otherOut = dec(d, "plannedOut").add(dec(d, "residualOut"));
            days.add(new BaseDay(java.time.LocalDate.parse(d.path("date").asText()),
                    dec(d, "plannedIn").add(dec(d, "recurringIn")).add(dec(d, "residualIn")), recurringOut, otherOut,
                    dec(d, "balance"), decOrNull(d, "balanceP10"), decOrNull(d, "balanceP50"),
                    decOrNull(d, "balanceP90")));
        }
        if (days.isEmpty()) {
            throw ApiException.unprocessable("EMPTY_FORECAST", "В базовом прогнозе нет дней");
        }
        return new Base(java.time.LocalDate.parse(forecast.path("asOfDate").asText()),
                new BigDecimal(forecast.path("startBalance").asText("0")),
                new BigDecimal(forecast.path("settings").path("bufferAmount").asText("0")), days);
    }

    private static BigDecimal dec(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? BigDecimal.ZERO.setScale(2) : new BigDecimal(v.asText());
    }

    private static BigDecimal decOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : new BigDecimal(v.asText());
    }

    private Change resolve(ChangeSpec c, Base base, JsonNode forecast,
                           ru.cashflow.orchestrator.features.companies.model.CompanyScope scope, UUID accountId) {
        var first = base.days().get(0).date();
        var last = base.days().get(base.days().size() - 1).date();
        switch (c.type()) {
            case DELAY_RECEIPT -> {
                if (c.delayDays() == null || c.delayDays() < 1 || c.delayDays() > MAX_DELAY_DAYS) {
                    throw bad("delayDays должен быть от 1 до " + MAX_DELAY_DAYS);
                }
                if (c.planId() != null) {
                    var plan = plans.find(scope, accountId, c.planId()).orElseThrow(ApiException::notFound);
                    if (plan.direction() != Direction.IN) {
                        throw bad("Задержать можно только ожидаемое поступление (direction = IN)");
                    }
                    if (plan.remainingAmount().signum() <= 0) {
                        throw bad("План уже оплачен или отменён");
                    }
                    if (!"WITH_KNOWN_PLANS".equals(forecast.path("scope").asText())) {
                        throw ApiException.unprocessable("PLAN_NOT_IN_FORECAST",
                                "Базовый прогноз построен без известных планов, поэтому платёж по плану в нём не "
                                        + "учтён. Постройте прогноз с учётом известных планов");
                    }
                    if (plan.dueDate().isBefore(first) || plan.dueDate().isAfter(last)) {
                        throw ApiException.unprocessable("PLAN_NOT_IN_FORECAST",
                                "Срок плана " + plan.dueDate() + " вне окна прогноза " + first + ".." + last);
                    }
                    return new Change(c.type(), titleOr(c, plan.title()), plan.id(), plan.remainingAmount(),
                            plan.dueDate(), c.delayDays(), null, null, null);
                }
                if (c.amount() == null || c.amount().signum() <= 0 || c.date() == null) {
                    throw bad("Для сдвига поступления укажите planId либо amount и date");
                }
                return new Change(c.type(), titleOr(c, "Задержка поступления"), null, c.amount(), c.date(),
                        c.delayDays(), null, null, null);
            }
            case REVENUE_DROP -> {
                if (c.percent() == null || c.percent().signum() <= 0 || c.percent().compareTo(MAX_DROP) > 0) {
                    throw bad("percent должен быть больше 0 и не больше 100");
                }
                return new Change(c.type(), titleOr(c, "Снижение выручки"), null, null, null, null, c.percent(),
                        from(c, first), null);
            }
            case UNPLANNED_EXPENSE -> {
                if (c.amount() == null || c.amount().signum() <= 0 || c.date() == null) {
                    throw bad("Для внепланового расхода укажите amount и date");
                }
                return new Change(c.type(), titleOr(c, "Внеплановый расход"), null, c.amount(), c.date(), null, null,
                        null, null);
            }
            case FIXED_COSTS_GROWTH -> {
                if (c.percent() == null || c.percent().signum() <= 0 || c.percent().compareTo(MAX_GROWTH) > 0) {
                    throw bad("percent должен быть больше 0 и не больше 300");
                }
                return new Change(c.type(), titleOr(c, "Рост постоянных расходов"), null, null, null, null,
                        c.percent(), from(c, first), c.target() == null ? CostTarget.RECURRING : c.target());
            }
            default -> throw bad("Неизвестный тип сценария");
        }
    }

    private static java.time.LocalDate from(ChangeSpec c, java.time.LocalDate first) {
        return c.fromDate() == null || c.fromDate().isBefore(first) ? first : c.fromDate();
    }

    private static String titleOr(ChangeSpec c, String fallback) {
        return c.title() == null || c.title().isBlank() ? fallback : c.title();
    }

    private static ApiException bad(String message) {
        return ApiException.unprocessable("INVALID_SCENARIO", message);
    }
}
