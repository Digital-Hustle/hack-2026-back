package ru.cashflow.orchestrator.units;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.Base;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.BaseDay;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.Change;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.CostTarget;
import ru.cashflow.orchestrator.features.scenarios.application.ScenarioEngine.Type;

class ScenarioEngineTest {
    static final LocalDate D1 = LocalDate.parse("2026-02-02");

    static BigDecimal m(String v) {
        return new BigDecimal(v);
    }

    /** 10 days, start 100 000: day 2 pays 80 000 (other), day 5 receives 150 000, day 8 pays 90 000 (other), day 3 rent 1 000. */
    static Base base() {
        List<BaseDay> days = new ArrayList<>();
        BigDecimal balance = m("100000.00");
        for (int i = 0; i < 10; i++) {
            BigDecimal in = i == 4 ? m("150000.00") : m("0.00");
            BigDecimal other = i == 1 ? m("80000.00") : i == 7 ? m("90000.00") : m("0.00");
            BigDecimal rec = i == 2 ? m("1000.00") : m("0.00");
            balance = balance.add(in).subtract(other).subtract(rec);
            days.add(new BaseDay(D1.plusDays(i), in, rec, other, balance, null, null, null));
        }
        return new Base(D1.minusDays(1), m("100000.00"), m("0.00"), days);
    }

    static Change delay(int days) {
        return new Change(Type.DELAY_RECEIPT, "t", null, m("150000.00"), D1.plusDays(4), days, null, null, null);
    }

    @Test
    void baseHasNoGap() {
        var r = ScenarioEngine.run(base(), List.of(delay(1)));
        assertThat(r.base().firstGapDate()).isNull();
        assertThat(r.base().minimumBalance()).isEqualByComparingTo("19000.00");
    }

    @Test
    void delayedReceiptCreatesGapWithDateAndAmount() {
        var r = ScenarioEngine.run(base(), List.of(delay(5)));
        // receipt moves from day 5 to day 10: day 8 pays 90 000 while only 19 000 is there
        assertThat(r.scenario().firstGapDate()).isEqualTo(D1.plusDays(7));
        assertThat(r.scenario().maxShortfall()).isEqualByComparingTo("71000.00");
        assertThat(r.scenario().daysBelowZero()).isEqualTo(2);
        assertThat(r.scenario().endBalance()).isEqualByComparingTo(r.base().endBalance());
        assertThat(r.effects().get(0).movedTo()).isEqualTo(D1.plusDays(9));
    }

    @Test
    void delayBeyondHorizonDropsTheReceiptFromTheWindow() {
        var r = ScenarioEngine.run(base(), List.of(delay(30)));
        assertThat(r.effects().get(0).droppedAmount()).isEqualByComparingTo("150000.00");
        assertThat(r.scenario().endBalance()).isEqualByComparingTo(r.base().endBalance().subtract(m("150000.00")));
    }

    @Test
    void revenueDropReducesEveryInflowFromTheStartDate() {
        var drop = new Change(Type.REVENUE_DROP, "t", null, null, null, null, m("30"), D1, null);
        var r = ScenarioEngine.run(base(), List.of(drop));
        assertThat(r.effects().get(0).movedAmount()).isEqualByComparingTo("45000.00");
        assertThat(r.scenario().endBalance()).isEqualByComparingTo(r.base().endBalance().subtract(m("45000.00")));
        var late = new Change(Type.REVENUE_DROP, "t", null, null, null, null, m("30"), D1.plusDays(6), null);
        assertThat(ScenarioEngine.run(base(), List.of(late)).effects().get(0).movedAmount()).isEqualByComparingTo("0");
    }

    @Test
    void unplannedExpenseIsSubtractedFromItsDateOnward() {
        var expense = new Change(Type.UNPLANNED_EXPENSE, "t", null, m("30000.00"), D1.plusDays(2), null, null, null, null);
        var r = ScenarioEngine.run(base(), List.of(expense));
        assertThat(r.days().get(1).delta()).isEqualByComparingTo("0");
        assertThat(r.days().get(2).delta()).isEqualByComparingTo("-30000.00");
        assertThat(r.days().get(9).delta()).isEqualByComparingTo("-30000.00");
        assertThat(r.scenario().minimumBalance()).isEqualByComparingTo("-11000.00");
        assertThat(r.scenario().firstGapDate()).isEqualTo(D1.plusDays(2));
    }

    @Test
    void fixedCostsGrowthTouchesOnlyRecurringUnlessAllOutflowsAreRequested() {
        var recurring = new Change(Type.FIXED_COSTS_GROWTH, "t", null, null, null, null, m("50"), D1, CostTarget.RECURRING);
        var all = new Change(Type.FIXED_COSTS_GROWTH, "t", null, null, null, null, m("50"), D1, CostTarget.ALL_OUTFLOWS);
        assertThat(ScenarioEngine.run(base(), List.of(recurring)).effects().get(0).movedAmount())
                .isEqualByComparingTo("500.00");
        assertThat(ScenarioEngine.run(base(), List.of(all)).effects().get(0).movedAmount())
                .isEqualByComparingTo("85500.00");
    }

    @Test
    void shiftsPercentilesByTheScenarioDelta() {
        var days = new ArrayList<>(base().days());
        var d = days.get(9);
        days.set(9, new BaseDay(d.date(), d.inflow(), d.recurringOut(), d.otherOut(), d.balance(),
                d.balance().subtract(m("10")), d.balance(), d.balance().add(m("10"))));
        var b = new Base(base().asOfDate(), base().startBalance(), base().buffer(), days);
        var expense = new Change(Type.UNPLANNED_EXPENSE, "t", null, m("100.00"), D1, null, null, null, null);
        var last = ScenarioEngine.run(b, List.of(expense)).days().get(9);
        assertThat(last.scenarioP10()).isEqualByComparingTo(d.balance().subtract(m("110.00")));
        assertThat(last.scenarioP90()).isEqualByComparingTo(d.balance().subtract(m("90.00")));
        assertThat(ScenarioEngine.run(b, List.of(expense)).days().get(0).scenarioP10()).isNull();
    }

    @Test
    void dateOutsideTheForecastWindowIsRejected() {
        var expense = new Change(Type.UNPLANNED_EXPENSE, "t", null, m("1.00"), D1.plusDays(20), null, null, null, null);
        assertThatThrownBy(() -> ScenarioEngine.run(base(), List.of(expense))).isInstanceOf(ApiException.class);
    }

    @Test
    void oneOffIncomeRaisesBalanceFromItsDate() {
        var credit = new Change(Type.UNPLANNED_INCOME, "t", null, m("1000.00"), D1.plusDays(1), null, null, null, null);
        var r = ScenarioEngine.run(base(), List.of(credit));
        assertThat(r.days().get(0).delta()).isEqualByComparingTo("0");
        assertThat(r.days().get(1).delta()).isEqualByComparingTo("1000.00");
        assertThat(r.scenario().endBalance()).isEqualByComparingTo("80000.00");
    }

    @Test
    void cancelledPlanDisappearsFromItsSide() {
        var income = new Change(Type.CANCEL_PLAN, "t", null, m("150000.00"), D1.plusDays(4), null, null, null, null,
                false);
        assertThat(ScenarioEngine.run(base(), List.of(income)).scenario().endBalance()).isEqualByComparingTo("-71000.00");
        var payment = new Change(Type.CANCEL_PLAN, "t", null, m("90000.00"), D1.plusDays(7), null, null, null, null,
                true);
        assertThat(ScenarioEngine.run(base(), List.of(payment)).scenario().endBalance()).isEqualByComparingTo("169000.00");
    }

    @Test
    void delayedPaymentMovesTheOutflowLater() {
        var delayed = new Change(Type.DELAY_PAYMENT, "t", null, m("90000.00"), D1.plusDays(7), 1, null, null, null, true);
        var r = ScenarioEngine.run(base(), List.of(delayed));
        assertThat(r.effects().get(0).movedTo()).isEqualTo(D1.plusDays(8));
        assertThat(r.days().get(7).scenarioBalance()).isEqualByComparingTo("169000.00");
        assertThat(r.days().get(8).scenarioBalance()).isEqualByComparingTo("79000.00");
    }

    @Test
    void maxPaymentIsTheLowestFutureBalanceAboveTheFloor() {
        var dates = base().days().stream().map(BaseDay::date).toList();
        var balances = base().days().stream().map(BaseDay::balance).toList();
        var fromStart = ScenarioEngine.maxPayment(dates, balances, D1, BigDecimal.ZERO);
        assertThat(fromStart.amount()).isEqualByComparingTo("19000.00");
        assertThat(fromStart.limitingDate()).isEqualTo(D1.plusDays(2));
        assertThat(ScenarioEngine.maxPayment(dates, balances, D1.plusDays(5), BigDecimal.ZERO).amount())
                .isEqualByComparingTo("79000.00");
        assertThat(ScenarioEngine.maxPayment(dates, balances, D1, m("10000.00")).amount()).isEqualByComparingTo("9000.00");
        var gap = ScenarioEngine.run(base(), List.of(delay(5))).days().stream()
                .map(ScenarioEngine.ScenarioDay::scenarioBalance).toList();
        assertThat(ScenarioEngine.maxPayment(dates, gap, D1, BigDecimal.ZERO).amount()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> ScenarioEngine.maxPayment(dates, balances, D1.plusDays(30), BigDecimal.ZERO))
                .isInstanceOf(ApiException.class);
    }
}
