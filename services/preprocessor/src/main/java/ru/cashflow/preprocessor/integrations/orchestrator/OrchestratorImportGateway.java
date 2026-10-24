package ru.cashflow.preprocessor.integrations.orchestrator;

import java.time.LocalDate;
import java.util.UUID;

import ru.cashflow.preprocessor.api.dto.response.ImportRs;
import ru.cashflow.preprocessor.api.dto.response.PreparedStatementRs;

/**
 * Port to Orchestrator imports. Staging is idempotent by key; finalize commits the staged package.
 * Implementations never send Authorization, cookies or any header except X-User-Id/Idempotency-Key/If-Match.
 */
public interface OrchestratorImportGateway {

    /**
     * @throws OrchestratorProblemException     Orchestrator rejected staging or finalize
     * @throws OrchestratorUnavailableException timeout, connection failure or malformed response
     */
    ImportUploadResult upload(ImportUploadCommand command);

    record ImportUploadCommand(UUID userId, UUID companyId, UUID accountId, String idempotencyKey, String fileName,
                               String fileSha256, String validationMode, String completeness,
                               LocalDate declaredFromDate, LocalDate declaredToDate, PreparedStatementRs statement) {
    }

    /** created is false when staging already returned a COMMITTED import for the same idempotency key. */
    record ImportUploadResult(ImportRs importRs, boolean created) {
    }
}
