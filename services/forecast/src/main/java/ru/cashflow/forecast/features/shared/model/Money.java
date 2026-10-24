package ru.cashflow.forecast.features.shared.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class Money {
    public static final BigDecimal ZERO = new BigDecimal("0.00");

    private Money() { }

    public static BigDecimal mean(BigDecimal sum, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Positive denominator required");
        }
        return cents(sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_EVEN));
    }

    public static BigDecimal quantile(List<BigDecimal> values, BigDecimal probability) {
        if (probability.signum() < 0 || probability.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Probability outside [0,1]");
        }
        if (values.isEmpty()) {
            return null;
        }
        var sorted = new ArrayList<>(values);
        sorted.sort(BigDecimal::compareTo);
        BigDecimal position = probability.multiply(BigDecimal.valueOf(sorted.size() - 1L));
        int lower = position.intValue();
        int upper = Math.min(lower + 1, sorted.size() - 1);
        BigDecimal fraction = position.subtract(BigDecimal.valueOf(lower));
        return cents(sorted.get(lower).add(sorted.get(upper).subtract(sorted.get(lower)).multiply(fraction)));
    }

    public static BigDecimal cents(BigDecimal amount) {
        var rounded = amount.setScale(2, RoundingMode.HALF_EVEN);
        if (rounded.precision() - rounded.scale() > 17) {
            throw new InputException("VALIDATION_FAILED", "amount", "Computed money exceeds NUMERIC(19,2)");
        }
        return rounded;
    }

}
