package ru.cashflow.preprocessor.features.statements.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.cashflow.preprocessor.features.statements.model.ParseIssue;

final class IssueCollector {

    private final int limit;
    private final List<ParseIssue> issues = new ArrayList<>();
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private long total;

    IssueCollector(int limit) {
        this.limit = limit;
    }

    void add(String code, String severity, String source, long record, long line, String field, String message) {
        total++;
        counts.merge(code, 1L, Long::sum);
        if (issues.size() < limit) {
            issues.add(new ParseIssue(code, severity, source, record, line, field, message));
        }
    }

    long total() {
        return total;
    }

    boolean truncated() {
        return total > issues.size();
    }

    Map<String, Long> counts() {
        return Map.copyOf(counts);
    }

    List<ParseIssue> issues() {
        return List.copyOf(issues);
    }
}
