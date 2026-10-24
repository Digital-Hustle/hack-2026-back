package ru.cashflow.preprocessor.api.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Public upload result; mirrors ImportRs of preprocessor-upload.openapi.yaml. */
public record ImportRs(UUID id, UUID companyId, UUID accountId, String status, String fileName,
                       String fileSha256, int totalRows, int acceptedRows, int rejectedRows, int duplicateRows,
                       int warningCount, LocalDate fromDate, LocalDate toDate, Instant createdAt, Instant completedAt,
                       Long dataVersion) {
}
