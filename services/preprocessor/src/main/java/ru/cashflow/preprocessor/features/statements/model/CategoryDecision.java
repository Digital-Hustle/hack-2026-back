package ru.cashflow.preprocessor.features.statements.model;

public record CategoryDecision(CategoryCode code, String ruleId, String rulesVersion) {

    public String source() {
        return ruleId == null ? "UNCLASSIFIED" : "RULE";
    }

    public boolean needsReview() {
        return code == CategoryCode.OTHER;
    }
}
