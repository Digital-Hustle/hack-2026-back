package ru.cashflow.forecast.units;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.features.forecast.application.GapDetector;
import ru.cashflow.forecast.features.risk.model.RiskAvailability;
import ru.cashflow.forecast.features.risk.model.RiskDay;
import ru.cashflow.forecast.features.risk.model.RiskEstimate;
import ru.cashflow.forecast.features.risk.model.RiskResult;
import ru.cashflow.forecast.features.shared.model.Money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GapDetectorTest {
    private final GapDetector detector = new GapDetector();

    @Test
    void groupsAdjacentNegativeDaysAndDoesNotDuplicateZeroBuffer() throws Exception {
        var point = TestInputs.engine().calculate(TestInputs.point());
        var risk = unavailable();
        var gaps = detector.detect("fixture", point, risk, Money.ZERO);
        assertThat(gaps).singleElement().satisfies(gap -> {
            assertThat(gap.kind()).isEqualTo("CASH_GAP");
            assertThat(gap.fromDate()).hasToString("2026-10-29");
            assertThat(gap.toDate()).hasToString("2026-11-22");
            assertThat(gap.expectedShortfall()).isEqualTo("50000.00");
            assertThat(gap.probability()).isNull();
        });
        assertThat(detector.detect("fixture", point, risk, Money.ZERO)).isEqualTo(gaps);
        assertThat(detector.detect("fixture", point, risk, new BigDecimal("10000.00")))
                .extracting(gap -> gap.kind()).containsExactly("CASH_GAP", "BUFFER_BREACH");
    }

    @Test
    void includesScenarioOnlyEpisodeAndComputesConditionalShortfallAmongBreaches() throws Exception {
        var point = TestInputs.engine().calculate(TestInputs.point(node -> node.withArray("knownPlans").removeAll()));
        var risks = new ArrayList<RiskDay>(unavailable().days());
        risks.set(0, new RiskDay(new BigDecimal("-40.00"), new BigDecimal("5.00"), new BigDecimal("10.00"), 0.5, 0.5));
        List<List<BigDecimal>> paths = List.of(path("-10.00"), path("-30.00"), path("10.00"), path("20.00"));
        var risk = new RiskResult(new RiskEstimate(RiskAvailability.AVAILABLE, "HISTORICAL_TRAJECTORY_BOOTSTRAP", 0.5, 4, 12, false, List.of()), risks, paths);
        assertThat(detector.detect("scenario", point, risk, Money.ZERO)).singleElement().satisfies(gap -> {
            assertThat(gap.expectedShortfall()).isEqualTo("0.00");
            assertThat(gap.probability()).isEqualTo(0.5);
            assertThat(gap.shortfallP50()).isEqualTo("20.00");
            assertThat(gap.shortfallP90()).isEqualTo("28.00");
            assertThat(gap.shortfallBasis()).isEqualTo("CONDITIONAL_ON_BREACH");
        });
    }

    @Test
    void rejectsShortfallOutsideMoneyRangeEvenWhenIndividualBalanceAndBufferFit() throws Exception {
        var point = TestInputs.engine().calculate(TestInputs.point(node -> {
            node.withArray("knownPlans").removeAll();
            node.put("startBalance", "-99999999999999999.99");
        }));
        assertThatThrownBy(() -> detector.detect("overflow", point, unavailable(), new BigDecimal("99999999999999999.99")))
                .isInstanceOf(ru.cashflow.forecast.features.shared.model.InputException.class);
    }

    private List<BigDecimal> path(String first) {
        var path = new ArrayList<>(java.util.Collections.nCopies(30, new BigDecimal("100.00")));
        path.set(0, new BigDecimal(first));
        return path;
    }

    private RiskResult unavailable() {
        return new RiskResult(new RiskEstimate(RiskAvailability.INSUFFICIENT_HISTORY, "HISTORICAL_TRAJECTORY_BOOTSTRAP", null, 0, 0, false, List.of()),
                java.util.Collections.nCopies(30, new RiskDay(null, null, null, null, null)), List.of());
    }
}
