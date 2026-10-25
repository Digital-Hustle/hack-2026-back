package ru.cashflow.forecast.units;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.features.backtest.model.ConfusionMatrix;
import ru.cashflow.forecast.features.shared.model.Money;

class ConfusionMatrixTest {
    @Test
    void derivesRatiosAndMajorityBaseline() {
        var matrix = ConfusionMatrix.of(Money.ZERO, 8, 2, 4, 86);
        assertThat(matrix.total()).isEqualTo(100);
        assertThat(matrix.accuracy()).isEqualTo(0.94);
        assertThat(matrix.precision()).isEqualTo(0.8);
        assertThat(matrix.recall()).isCloseTo(8.0 / 12, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(matrix.specificity()).isCloseTo(86.0 / 88, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(matrix.f1()).isCloseTo(16.0 / 22, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(matrix.prevalence()).isEqualTo(0.12);
        assertThat(matrix.majorityBaselineAccuracy()).isEqualTo(0.88);
    }

    @Test
    void undefinedDenominatorsAreNullNotPerfect() {
        var noGaps = ConfusionMatrix.of(Money.ZERO, 0, 0, 0, 30);
        assertThat(noGaps.accuracy()).isEqualTo(1.0);
        assertThat(noGaps.precision()).isNull();
        assertThat(noGaps.recall()).isNull();
        assertThat(noGaps.f1()).isNull();
        assertThat(noGaps.balancedAccuracy()).isNull();
        var empty = ConfusionMatrix.of(Money.ZERO, 0, 0, 0, 0);
        assertThat(empty.accuracy()).isNull();
        assertThat(empty.majorityBaselineAccuracy()).isNull();
    }
}
