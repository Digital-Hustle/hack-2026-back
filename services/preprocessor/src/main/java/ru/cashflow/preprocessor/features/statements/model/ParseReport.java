package ru.cashflow.preprocessor.features.statements.model;

import java.util.List;
import java.util.Map;

public record ParseReport(String status, int rowsTotal, int rowsAccepted, int rowsRejected, int rowsDuplicate,
                          int unclassifiedRows, long issueCount, boolean issuesTruncated,
                          Map<String, Long> issueCounts, List<ParseIssue> issues) {}
