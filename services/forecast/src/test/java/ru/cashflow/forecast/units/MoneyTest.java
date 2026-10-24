package ru.cashflow.forecast.units;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashflow.forecast.features.shared.model.Money;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {
    @Test
    void averagesInCentsWithHalfEvenAndRejectsEmptyDenominator() {
        assertThat(Money.mean(new BigDecimal("0.01"), 2)).isEqualTo("0.00");
        assertThat(Money.mean(new BigDecimal("0.03"), 2)).isEqualTo("0.02");
        assertThat(Money.mean(new BigDecimal("99999999999999999.99"), 1))
                .isEqualTo("99999999999999999.99");
        assertThatThrownBy(() -> Money.mean(Money.ZERO, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quantileInterpolatesSortedMoneyWithoutBinaryFloatingPoint() {
        var values = List.of(new BigDecimal("100.00"), new BigDecimal("0.00"));
        assertThat(Money.quantile(values, new BigDecimal("0.1"))).isEqualTo("10.00");
        assertThat(Money.quantile(values, new BigDecimal("0.5"))).isEqualTo("50.00");
        assertThat(Money.quantile(List.of(), new BigDecimal("0.5"))).isNull();
    }

    @Test
    void rejectsMonetaryOutputsOutsideNumeric19Scale2RatherThanReturningUnstorableMoney() {
        assertThatThrownBy(() -> Money.cents(new BigDecimal("100000000000000000.00")))
                .isInstanceOf(ru.cashflow.forecast.features.shared.model.InputException.class);
    }
}
