package ru.cashflow.preprocessor.api.dto.response;

public record CategoryRs(String code, String title, String source, String ruleId, String rulesVersion,
                         boolean needsReview) {}
