package ru.cashflow.forecast.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Integer restricted to an explicit set of supported values. */
@Documented
@Constraint(validatedBy = AllowedIntsValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE,
        ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowedInts {

    int[] value();

    String message() default "unsupported value";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
