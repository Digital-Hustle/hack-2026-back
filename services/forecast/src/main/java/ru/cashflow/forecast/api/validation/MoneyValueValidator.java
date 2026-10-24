package ru.cashflow.forecast.api.validation;

import java.math.BigDecimal;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class MoneyValueValidator implements ConstraintValidator<MoneyValue, BigDecimal> {

    private static final int MAX_INTEGER_DIGITS = 17;
    private MoneyValue.Sign sign;

    @Override
    public void initialize(MoneyValue annotation) {
        sign = annotation.sign();
    }

    @Override
    public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value.scale() != 2 || value.precision() - value.scale() > MAX_INTEGER_DIGITS) {
            return false;
        }
        return switch (sign) {
            case ANY -> true;
            case NON_NEGATIVE -> value.signum() >= 0;
            case POSITIVE -> value.signum() > 0;
        };
    }
}
