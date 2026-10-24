package ru.cashflow.orchestrator.api.controller;

import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.bind.annotation.RestController;

import ru.cashflow.orchestrator.api.dto.CompanyDtos.ItemsRs;
import ru.cashflow.orchestrator.api.dto.ImportDtos.ImportRs;
import ru.cashflow.orchestrator.api.dto.ImportDtos.PreparedStatementRq;
import ru.cashflow.orchestrator.api.mapper.PreparedStatementMapper;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.imports.application.ImportService;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.ImportRecord;
import ru.cashflow.orchestrator.features.imports.model.ImportTypes.StagedIssue;

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
                                          @Valid @RequestBody PreparedStatementRq rq) {
        ImportRecord record = service.stage(user.userId(), companyId, accountId, key,
                account -> PreparedStatementMapper.toStaged(rq, account.externalCompanyId()), hash(rq));
        return ResponseEntity.status(HttpStatus.CREATED).body(of(record));
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
    public ItemsRs<ImportRs> list(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId) {
        return new ItemsRs<>(service.list(user.userId(), companyId, accountId).stream().map(ImportController::of)
                .toList());
    }

    @GetMapping("/{importId}")
    public ImportRs get(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                        @PathVariable UUID importId) {
        return of(service.get(user.userId(), companyId, accountId, importId));
    }

    @GetMapping("/{importId}/issues")
    public ItemsRs<StagedIssue> issues(CurrentUser user, @PathVariable UUID companyId, @PathVariable UUID accountId,
                                       @PathVariable UUID importId) {
        return new ItemsRs<>(service.issues(user.userId(), companyId, accountId, importId));
    }

    private static String hash(PreparedStatementRq rq) {
        try {
            return ImportService.sha256(CANONICAL.writeValueAsString(rq));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ImportRs of(ImportRecord r) {
        return new ImportRs(r.id(), r.accountId(), r.status().name(), r.preprocessorStatus(), r.rowsStaged(),
                r.rowsInserted(), r.rowsAlreadyPresent(), r.issueCount(), r.inputFingerprint(), r.createdAt(),
                r.finalizedAt(), r.version());
    }
}
