package ru.cashflow.preprocessor.api.dto.response;

import java.time.Instant;
import java.util.List;

import ru.cashflow.preprocessor.features.statements.model.CompanyInfo;
import ru.cashflow.preprocessor.features.statements.model.ParseReport;

public record PreparedStatementRs(String schemaVersion, String rulesVersion, String inputFingerprint,
                                  Instant preparedAt, List<CompanyInfo> companies, List<OperationRs> operations,
                                  List<DaySummaryRs> dailyBalances, ParseReport report) {}
