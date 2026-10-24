package ru.cashflow.forecast.api.validation;

import java.util.Arrays;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class AllowedIntsValidator implements ConstraintValidator<AllowedInts, Integer> {

    private int[] allowed;

    @Override
    public void initialize(AllowedInts annotation) {
        allowed = annotation.value().clone();
    }

    @Override
    public boolean isValid(Integer value, ConstraintValidatorContext context) {
        return value == null || Arrays.stream(allowed).anyMatch(candidate -> candidate == value);
    }
}
