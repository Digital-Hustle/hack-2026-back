package ru.cashflow.orchestrator.features.scenarios.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import ru.cashflow.orchestrator.api.error.ApiException;

/**
 * Pure "what if" arithmetic over an already computed base forecast. It never touches the database and never calls the
 * forecast service: every scenario is a deterministic transform of the per-day flows of the stored forecast.
 */
public final class ScenarioEngine {

    public enum Type { DELAY_RECEIPT, REVENUE_DROP, UNPLANNED_EXPENSE, FIXED_COSTS_GROWTH }

    public enum CostTarget { RECURRING, ALL_OUTFLOWS }

    /** A fully resolved change: plan references are already turned into amount and date by the caller. */
    public record Change(Type type, String title, UUID planId, BigDecimal amount, LocalDate date, Integer delayDays,
                         BigDecimal percent, LocalDate fromDate, CostTarget target) {
    }

    public record BaseDay(LocalDate date, BigDecimal inflow, BigDecimal recurringOut, BigDecimal otherOut,
                          BigDecimal balance, BigDecimal p10, BigDecimal p50, BigDecimal p90) {
    }

    public record Base(LocalDate asOfDate, BigDecimal startBalance, BigDecimal buffer, List<BaseDay> days) {
    }

    public record ScenarioDay(LocalDate date, BigDecimal baseInflow, BigDecimal scenarioInflow,
                              BigDecimal baseOutflow, BigDecimal scenarioOutflow, BigDecimal baseBalance,
                              BigDecimal scenarioBalance, BigDecimal delta, BigDecimal scenarioP10,
                              BigDecimal scenarioP50, BigDecimal scenarioP90) {
    }

    public record Summary(BigDecimal minimumBalance, LocalDate minimumBalanceDate, BigDecimal endBalance,
                          LocalDate firstGapDate, BigDecimal balanceOnFirstGapDate, BigDecimal maxShortfall,
                          LocalDate maxShortfallDate, int daysBelowZero, LocalDate firstBufferBreachDate) {
    }

    public record Effect(Change change, BigDecimal movedAmount, BigDecimal droppedAmount, LocalDate movedTo,
                         BigDecimal endBalanceDelta, BigDecimal minimumBalanceDelta, String note) {
    }

    public record Result(List<ScenarioDay> days, Summary base, Summary scenario, List<Effect> effects) {
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private ScenarioEngine() {
    }

    public static Result run(Base base, List<Change> changes) {
        Flow flow = Flow.of(base);
        List<Effect> effects = new ArrayList<>();
        for (Change change : changes) {
            Applied applied = apply(flow, change);
            // Effect of the change on its own, so the UI can show what each lever costs.
            Flow alone = Flow.of(base);
            apply(alone, change);
            var aloneBalances = alone.balances(base);
            var baseBalances = base.days().stream().map(BaseDay::balance).toList();
            BigDecimal endDelta = aloneBalances.get(aloneBalances.size() - 1)
                    .subtract(baseBalances.get(baseBalances.size() - 1));
            BigDecimal minDelta = min(aloneBalances).subtract(min(baseBalances));
            effects.add(new Effect(change, applied.moved, applied.dropped, applied.movedTo, endDelta, minDelta,
                    applied.note));
        }
        var balances = flow.balances(base);
        var days = new ArrayList<ScenarioDay>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (int i = 0; i < base.days().size(); i++) {
            BaseDay b = base.days().get(i);
            BigDecimal baseOut = b.recurringOut().add(b.otherOut());
            BigDecimal scenarioOut = flow.recurringOut[i].add(flow.otherOut[i]);
            BigDecimal delta = balances.get(i).subtract(b.balance());
            cumulative = delta;
            days.add(new ScenarioDay(b.date(), b.inflow(), flow.in[i], baseOut, scenarioOut, b.balance(),
                    balances.get(i), delta, shift(b.p10(), cumulative), shift(b.p50(), cumulative),
                    shift(b.p90(), cumulative)));
        }
        return new Result(days, summarize(base.days().stream().map(BaseDay::date).toList(),
                base.days().stream().map(BaseDay::balance).toList(), base.buffer()),
                summarize(base.days().stream().map(BaseDay::date).toList(), balances, base.buffer()), effects);
    }

    private static BigDecimal shift(BigDecimal value, BigDecimal delta) {
        return value == null ? null : value.add(delta);
    }

    private static BigDecimal min(List<BigDecimal> values) {
        return values.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    static Summary summarize(List<LocalDate> dates, List<BigDecimal> balances, BigDecimal buffer) {
        BigDecimal min = null;
        LocalDate minDate = null;
        LocalDate firstGap = null;
        BigDecimal onFirstGap = null;
        LocalDate firstBuffer = null;
        int below = 0;
        for (int i = 0; i < balances.size(); i++) {
            BigDecimal balance = balances.get(i);
            if (min == null || balance.compareTo(min) < 0) {
                min = balance;
                minDate = dates.get(i);
            }
            if (balance.signum() < 0) {
                below++;
                if (firstGap == null) {
                    firstGap = dates.get(i);
                    onFirstGap = balance;
                }
            }
            if (buffer != null && buffer.signum() > 0 && firstBuffer == null && balance.compareTo(buffer) < 0) {
                firstBuffer = dates.get(i);
            }
        }
        BigDecimal shortfall = min != null && min.signum() < 0 ? min.negate() : BigDecimal.ZERO.setScale(2);
        return new Summary(min, minDate, balances.isEmpty() ? null : balances.get(balances.size() - 1), firstGap,
                onFirstGap, shortfall, shortfall.signum() > 0 ? minDate : null, below, firstBuffer);
    }

    private static final class Applied {
        BigDecimal moved;
        BigDecimal dropped;
        LocalDate movedTo;
        String note;
    }

    private static Applied apply(Flow flow, Change c) {
        Applied applied = new Applied();
        switch (c.type()) {
            case DELAY_RECEIPT -> {
                int from = flow.indexOf(c.date());
                if (from < 0) {
                    throw ApiException.unprocessable("SCENARIO_DATE_OUTSIDE_FORECAST",
                            "Дата поступления " + c.date() + " вне горизонта базового прогноза");
                }
                BigDecimal moved = c.amount().min(flow.in[from]).max(BigDecimal.ZERO);
                flow.in[from] = flow.in[from].subtract(moved);
                LocalDate target = c.date().plusDays(c.delayDays());
                int to = flow.indexOf(target);
                applied.moved = moved;
                applied.movedTo = target;
                if (to >= 0) {
                    flow.in[to] = flow.in[to].add(moved);
                    applied.dropped = BigDecimal.ZERO.setScale(2);
                } else {
                    applied.dropped = moved;
                    applied.note = "Оплата сдвигается за конец горизонта прогноза и в нём не учитывается";
                }
                if (moved.compareTo(c.amount()) < 0) {
                    applied.note = "В базовом прогнозе на эту дату поступлений меньше указанной суммы: сдвинуто "
                            + moved.toPlainString();
                }
            }
            case REVENUE_DROP -> {
                BigDecimal factor = BigDecimal.ONE.subtract(c.percent().divide(HUNDRED, 6, RoundingMode.HALF_UP));
                BigDecimal removed = BigDecimal.ZERO;
                for (int i = 0; i < flow.dates.length; i++) {
                    if (!flow.dates[i].isBefore(c.fromDate())) {
                        BigDecimal next = flow.in[i].multiply(factor).setScale(2, RoundingMode.HALF_UP);
                        removed = removed.add(flow.in[i].subtract(next));
                        flow.in[i] = next;
                    }
                }
                applied.moved = removed;
                applied.dropped = removed;
                if (removed.signum() == 0) {
                    applied.note = "В базовом прогнозе нет поступлений в выбранном периоде, снижать нечего";
                }
            }
            case FIXED_COSTS_GROWTH -> {
                BigDecimal factor = BigDecimal.ONE.add(c.percent().divide(HUNDRED, 6, RoundingMode.HALF_UP));
                BigDecimal added = BigDecimal.ZERO;
                for (int i = 0; i < flow.dates.length; i++) {
                    if (!flow.dates[i].isBefore(c.fromDate())) {
                        BigDecimal rec = flow.recurringOut[i].multiply(factor).setScale(2, RoundingMode.HALF_UP);
                        added = added.add(rec.subtract(flow.recurringOut[i]));
                        flow.recurringOut[i] = rec;
                        if (c.target() == CostTarget.ALL_OUTFLOWS) {
                            BigDecimal oth = flow.otherOut[i].multiply(factor).setScale(2, RoundingMode.HALF_UP);
                            added = added.add(oth.subtract(flow.otherOut[i]));
                            flow.otherOut[i] = oth;
                        }
                    }
                }
                applied.moved = added;
                applied.dropped = BigDecimal.ZERO.setScale(2);
                if (added.signum() == 0) {
                    applied.note = c.target() == CostTarget.RECURRING
                            ? "В базовом прогнозе нет регулярных расходов; для роста всех расходов укажите "
                                    + "target = ALL_OUTFLOWS"
                            : "В базовом прогнозе нет расходов в выбранном периоде";
                }
            }
            case UNPLANNED_EXPENSE -> {
                int i = flow.indexOf(c.date());
                if (i < 0) {
                    throw ApiException.unprocessable("SCENARIO_DATE_OUTSIDE_FORECAST",
                            "Дата расхода " + c.date() + " вне горизонта базового прогноза");
                }
                flow.otherOut[i] = flow.otherOut[i].add(c.amount());
                applied.moved = c.amount();
                applied.dropped = BigDecimal.ZERO.setScale(2);
            }
            default -> throw new IllegalStateException("Unknown scenario type " + c.type());
        }
        return applied;
    }

    private static final class Flow {
        LocalDate[] dates;
        BigDecimal[] in;
        BigDecimal[] recurringOut;
        BigDecimal[] otherOut;

        static Flow of(Base base) {
            int n = base.days().size();
            Flow f = new Flow();
            f.dates = new LocalDate[n];
            f.in = new BigDecimal[n];
            f.recurringOut = new BigDecimal[n];
            f.otherOut = new BigDecimal[n];
            for (int i = 0; i < n; i++) {
                BaseDay d = base.days().get(i);
                f.dates[i] = d.date();
                f.in[i] = d.inflow();
                f.recurringOut[i] = d.recurringOut();
                f.otherOut[i] = d.otherOut();
            }
            return f;
        }

        int indexOf(LocalDate date) {
            for (int i = 0; i < dates.length; i++) {
                if (dates[i].equals(date)) {
                    return i;
                }
            }
            return -1;
        }

        /** Scenario balance = base balance + accumulated change of net flow, so the base path stays authoritative. */
        List<BigDecimal> balances(Base base) {
            List<BigDecimal> result = new ArrayList<>();
            BigDecimal cumulative = BigDecimal.ZERO;
            for (int i = 0; i < dates.length; i++) {
                BaseDay b = base.days().get(i);
                BigDecimal dIn = in[i].subtract(b.inflow());
                BigDecimal dOut = recurringOut[i].add(otherOut[i]).subtract(b.recurringOut().add(b.otherOut()));
                cumulative = cumulative.add(dIn).subtract(dOut);
                result.add(b.balance().add(cumulative));
            }
            return result;
        }
    }
}
