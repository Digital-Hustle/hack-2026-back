package ru.cashflow.orchestrator.api.dto;

import java.util.List;
import java.util.function.Function;

import ru.cashflow.orchestrator.features.paging.model.PageQuery;
import ru.cashflow.orchestrator.features.paging.model.PageResult;

/** Contract page envelope: items plus a 0-based PageMeta. */
public final class PageDtos {

    private PageDtos() {
    }

    public record PageMeta(int page, int size, long total, boolean hasNext) {

        public static PageMeta of(PageQuery query, long total) {
            return new PageMeta(query.page(), query.size(), total,
                    query.offset() + query.size() < total);
        }
    }

    public record PageRs<T>(List<T> items, PageMeta page) {

        public static <S, T> PageRs<T> of(PageResult<S> result, PageQuery query, Function<S, T> mapper) {
            return new PageRs<>(result.items().stream().map(mapper).toList(), PageMeta.of(query, result.total()));
        }
    }
}