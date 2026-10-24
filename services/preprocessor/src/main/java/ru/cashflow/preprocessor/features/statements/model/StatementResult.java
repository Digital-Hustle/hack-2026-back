package ru.cashflow.preprocessor.features.statements.model;

import java.time.Instant;
import java.util.List;

public record StatementResult(String schemaVersion, String rulesVersion, String inputFingerprint,
                              Instant preparedAt, List<CompanyInfo> companies, List<ParsedOperation> operations,
                              List<DaySummary> dailyBalances, ParseReport report) {}
