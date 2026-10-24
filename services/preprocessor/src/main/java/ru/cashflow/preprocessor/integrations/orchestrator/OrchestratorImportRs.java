package ru.cashflow.preprocessor.integrations.orchestrator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import ru.cashflow.preprocessor.api.dto.response.ImportRs;

/** Wire representation of the actual Orchestrator ImportDtos.ImportRs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrchestratorImportRs(UUID id, UUID accountId, String status, String preprocessorStatus,
                                   int rowsStaged, Integer rowsInserted, Integer rowsAlreadyPresent, long issueCount,
                                   String inputFingerprint, Instant createdAt, Instant finalizedAt, Long version) {

    public boolean finalized() {
        return "FINALIZED".equals(status);
    }

    public boolean staged() {
        return "STAGED".equals(status);
    }

    public ImportRs toPublic(OrchestratorImportGateway.ImportUploadCommand command) {
        var report = command.statement().report();
        LocalDate from = command.statement().operations().stream().map(op -> op.operationDate())
                .min(Comparator.naturalOrder()).orElse(null);
        LocalDate to = command.statement().operations().stream().map(op -> op.operationDate())
                .max(Comparator.naturalOrder()).orElse(null);
        String publicStatus = switch (status) {
            case "STAGED" -> "STAGING";
            case "FINALIZED" -> "COMMITTED";
            case "CANCELLED" -> "ABORTED";
            default -> throw new OrchestratorUnavailableException("Неизвестный статус импорта");
        };
        int warnings = (int) Math.min(Integer.MAX_VALUE, report.warningCount());
        int accepted = finalized() ? rowsInserted : rowsStaged;
        int duplicates = report.rowsDuplicate() + (rowsAlreadyPresent == null ? 0 : rowsAlreadyPresent);
        return new ImportRs(id, command.companyId(), accountId, publicStatus, command.fileName(),
                command.fileSha256(), report.rowsTotal(), accepted, report.rowsRejected(), duplicates,
                warnings, from, to, createdAt, finalizedAt, null);
    }
}
