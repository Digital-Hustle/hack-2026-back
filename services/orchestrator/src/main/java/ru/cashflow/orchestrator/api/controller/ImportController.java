package ru.cashflow.orchestrator.api.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.ImportDtos.ImportIssueRs;
import ru.cashflow.orchestrator.api.dto.ImportDtos.ImportRs;
import ru.cashflow.orchestrator.api.dto.ImportDtos.PreparedStatementRq;
import ru.cashflow.orchestrator.api.dto.PageDtos.PageRs;
import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.api.mapper.PreparedStatementMapper;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.imports.application.ImportService;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ImportRecord;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ImportStatus;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ImportOptions;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Completeness;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedIssue;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ValidationMode;
import ru.cashflow.orchestrator.features.paging.model.PageQuery;

@RestController
@RequestMapping("/api/v1/orchestrator/companies/{companyId}/accounts/{accountId}/imports")
public class ImportController {

    private static final ObjectMapper CANONICAL = JsonMapper.builder().findAndAddModules()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true).build();

    private final ImportService service;

    public ImportController(ImportService service) {
        this.service = service;
    }

    /** Body is the PreProcessor JSON unchanged. Nothing reaches history until POST .../finalize. */
    @PostMapping
    public ResponseEntity<ImportRs> stage(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                          @RequestHeader("Idempotency-Key") String key,
                                          @RequestParam(required = false) String fileName,
                                          @RequestParam(required = false) String fileSha256,
                                          @RequestParam(defaultValue = "SKIP_INVALID") ValidationMode validationMode,
                                          @RequestParam(defaultValue = "UNKNOWN") Completeness completeness,
                                          @RequestParam(required = false) LocalDate declaredFromDate,
                                          @RequestParam(required = false) LocalDate declaredToDate,
                                          @Valid @RequestBody PreparedStatementRq rq) {
        var report = rq.report();
        int total = report.rowsTotal() > 0 ? report.rowsTotal() : rq.operations().size();
        var options = new ImportOptions(fileName, fileSha256, validationMode, completeness,
                declaredFromDate, declaredToDate, total, report.rowsRejected(), report.rowsDuplicate(),
                report.warningCount());
        ImportRecord record = service.stage(user.userId(), companyId, accountId, key, options,
                account -> PreparedStatementMapper.toStaged(rq, account.externalCompanyId()), hash(rq, options));
        if (record.status() == ImportStatus.REJECTED) {
            throw ApiException.unprocessable("IMPORT_REJECTED",
                    "Импорт отклонён: проверьте отчёт об ошибках", record.id());
        }
        return ResponseEntity.status(record.status() == ImportStatus.COMMITTED ? HttpStatus.OK : HttpStatus.CREATED)
                .eTag("\"" + record.version() + "\"").body(of(record));
    }

    @PostMapping("/{importId}/finalize")
    public ImportRs finalizeImport(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                   @PathVariable UUID importId, @RequestHeader("If-Match") String ifMatch) {
        return of(service.finalizeImport(user.userId(), companyId, accountId, importId,
                CompanyController.version(ifMatch)));
    }

    @PostMapping("/{importId}/cancel")
    public ImportRs cancel(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                           @PathVariable UUID importId) {
        return of(service.cancel(user.userId(), companyId, accountId, importId));
    }

    @GetMapping
    public PageRs<ImportRs> list(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                 @RequestParam(required = false) Integer page,
                                 @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        return PageRs.of(service.list(user.userId(), companyId, accountId, query), query, ImportController::of);
    }

    @GetMapping("/{importId}")
    public ImportRs get(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                        @PathVariable UUID importId) {
        return of(service.get(user.userId(), companyId, accountId, importId));
    }

    @GetMapping("/{importId}/issues")
    public PageRs<ImportIssueRs> issues(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                        @PathVariable UUID importId,
                                        @RequestParam(required = false) String severity,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        PageQuery query = PageQuery.of(page, size);
        return PageRs.of(service.issues(user.userId(), companyId, accountId, importId, severity, query), query,
                issue -> issue(importId, issue));
    }

    private static String hash(PreparedStatementRq rq, ImportOptions options) {
        try {
            // preparedAt varies on each PreProcessor retry; the financial payload and options do not.
            ObjectNode stable = CANONICAL.valueToTree(rq);
            stable.remove("preparedAt");
            return ImportService.sha256(CANONICAL.writeValueAsString(new Object[] {stable, options}));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static ImportRs of(ImportRecord r) {
        return new ImportRs(r.id(), r.companyId(), r.accountId(), r.status().name(), r.fileName(), r.fileSha256(),
                r.totalRows(), r.acceptedRows(), r.rejectedRows(), r.duplicateRows(), r.warningCount(),
                r.fromDate(), r.toDate(), r.createdAt(), r.finalizedAt(), r.dataVersion());
    }

    static ImportIssueRs issue(UUID importId, StagedIssue issue) {
        Integer rowNumber = issue.sourceLine() == null || issue.sourceLine() < 1 ? null
                : issue.sourceLine() > Integer.MAX_VALUE ? Integer.MAX_VALUE : issue.sourceLine().intValue();
        UUID id = UUID.nameUUIDFromBytes((importId + ":" + issue.seq()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ImportIssueRs(id, severity(issue.severity()), issue.code(), rowNumber, issue.field(),
                issue.message());
    }

    private static String severity(String value) {
        if ("INFO".equals(value) || "WARNING".equals(value) || "ERROR".equals(value)) {
            return value;
        }
        return "WARNING";
    }

}
