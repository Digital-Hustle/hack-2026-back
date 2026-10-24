package ru.cashflow.forecast.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** RUB amount with exactly two decimals and at most 17 integer digits (NUMERIC(19,2)). */
@Documented
@Constraint(validatedBy = MoneyValueValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE,
        ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface MoneyValue {

    Sign sign() default Sign.ANY;

    String message() default "must be a decimal amount with exactly two fraction digits";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    enum Sign { ANY, NON_NEGATIVE, POSITIVE }
}
