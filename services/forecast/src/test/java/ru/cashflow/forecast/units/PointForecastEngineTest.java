package ru.cashflow.forecast.units;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.features.forecast.application.PointForecastEngine;
import ru.cashflow.forecast.features.shared.model.DataQuality;
import static org.assertj.core.api.Assertions.assertThat;

class PointForecastEngineTest {
    private final PointForecastEngine engine = TestInputs.engine();

    @Test
    void calculatesThirtyDaysAndMatchesHandComputedFixture() throws Exception {
        var result = engine.calculate(TestInputs.point());
        assertThat(result.days()).hasSize(30);
        assertThat(result.days().getFirst().date()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(result.days().getLast().date()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(result.days().getFirst().balance()).isEqualTo("102000.00");
        assertThat(result.days().get(4).balance()).isEqualTo("-50000.00");
        assertThat(result.days().getLast().balance()).isEqualTo("0.00");
        assertThat(result.expectedMinimumBalance()).isEqualTo("-50000.00");
        assertThat(result.firstExpectedGapDate()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(result.maxExpectedShortfall()).isEqualTo("50000.00");
        assertThat(result.quality()).isEqualTo(DataQuality.LIMITED);
        for (int index = 0; index < 30; index++) {
            var day = result.days().get(index);
            BigDecimal previous = index == 0 ? new BigDecimal("100000.00") : result.days().get(index - 1).balance();
            assertThat(day.balance()).isEqualTo(previous.add(day.inflow()).subtract(day.outflow()));
            assertThat(day.inflow()).isEqualTo(day.plannedIn().add(day.recurringIn()).add(day.residualIn()));
        }
    }

    @Test
    void doesNotExtendStartBalanceTwiceAndRoundsComponentsBeforeAccumulation() throws Exception {
        var earlierAnchor = engine.calculate(TestInputs.point(node -> node.put("startBalanceDate", "2026-10-20")));
        assertThat(earlierAnchor.days().getFirst().balance()).isEqualTo("102000.00");
        ObjectNode node = TestInputs.synthetic(LocalDate.of(2026, 10, 24), 14, "0.00", "0.00");
        ObjectNode first = (ObjectNode) node.withArray("dailyHistory").get(0);
        first.put("inflow", "0.01");
        first.put("status", "OBSERVED");
        first.put("paymentCount", 1);
        var payment = node.withArray("payments").addObject();
        payment.put("paymentId", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        payment.put("date", "2026-10-11");
        payment.put("amount", "0.01");
        payment.put("direction", "IN");
        payment.put("categoryCode", "SALES");
        payment.putNull("counterpartyKey");
        var result = engine.calculate(TestInputs.point(node));
        assertThat(result.days().getFirst().residualIn()).isEqualTo("0.00");
        assertThat(result.days().getLast().balance()).isEqualTo("100000.00");
    }

    @Test
    void removesReplacedBaselineAcrossEntireHorizon() throws Exception {
        var input = TestInputs.point(node -> {
            ObjectNode plan = (ObjectNode) node.withArray("knownPlans").get(0);
            plan.put("relation", "REPLACES_BASELINE");
            plan.put("baselineCounterpartyKey", "supplier-a");
        });
        var result = engine.calculate(input);
        assertThat(result.days()).allSatisfy(day -> assertThat(day.residualOut()).isEqualTo("0.00"));
        assertThat(result.days().getLast().balance()).isEqualTo("240000.00");
    }

    @Test
    void negativeInitialBalanceIsExplicitAndForecastRemainsDeterministic() throws Exception {
        var input = TestInputs.point(node -> node.put("startBalance", "-100000.00"));
        var first = engine.calculate(input);
        assertThat(first.warnings()).extracting(warning -> warning.code()).contains("INITIAL_CASH_GAP");
        assertThat(engine.calculate(input)).isEqualTo(first);
    }
}
