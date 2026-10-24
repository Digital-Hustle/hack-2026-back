package ru.cashflow.orchestrator.features.paging.model;

import ru.cashflow.orchestrator.api.error.ApiException;

/** Validated 0-based page request of the public contract (default 50, maximum 200). */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    public static PageQuery of(Integer page, Integer size) {
        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedPage < 0 || resolvedSize < 1 || resolvedSize > MAX_SIZE) {
            throw ApiException.unprocessable("INVALID_PAGE", "page >= 0, size 1..200");
        }
        return new PageQuery(resolvedPage, resolvedSize);
    }

    public long offset() {
        return (long) page * size;
    }

    public long endExclusive() {
        return offset() + size;
    }
}