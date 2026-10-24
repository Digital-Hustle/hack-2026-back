package ru.cashflow.preprocessor.api.mapper;

import java.math.BigDecimal;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import ru.cashflow.preprocessor.api.dto.response.CategoryRs;
import ru.cashflow.preprocessor.api.dto.response.DaySummaryRs;
import ru.cashflow.preprocessor.api.dto.response.OperationRs;
import ru.cashflow.preprocessor.api.dto.response.PreparedStatementRs;
import ru.cashflow.preprocessor.features.statements.model.CategoryDecision;
import ru.cashflow.preprocessor.features.statements.model.DaySummary;
import ru.cashflow.preprocessor.features.statements.model.ParsedOperation;
import ru.cashflow.preprocessor.features.statements.model.StatementResult;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface StatementMapper {

    PreparedStatementRs response(StatementResult result);

    OperationRs operation(ParsedOperation operation);

    DaySummaryRs day(DaySummary day);

    @Mapping(target = "code", expression = "java(decision.code().name())")
    @Mapping(target = "title", expression = "java(decision.code().title())")
    @Mapping(target = "source", expression = "java(decision.source())")
    @Mapping(target = "needsReview", expression = "java(decision.needsReview())")
    CategoryRs category(CategoryDecision decision);

    default String money(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }
}
