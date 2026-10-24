package ru.cashflow.orchestrator.features.paging.model;

import java.util.List;

/** One page of a list response together with the total row count before LIMIT/OFFSET. */
public record PageResult<T>(List<T> items, long total) {

    public static <T> PageResult<T> of(List<T> items, long total) {
        return new PageResult<>(items, total);
    }
}