package ru.cashflow.preprocessor.api.controller;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import ru.cashflow.preprocessor.api.dto.response.ImportRs;
import ru.cashflow.preprocessor.api.security.CurrentUser;

@RequestMapping("/api/v1/preprocessor/companies/{companyId}")
public interface ImportController {

    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ImportRs> upload(CurrentUser user, @PathVariable UUID companyId,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                    @RequestPart(value = "file", required = false) MultipartFile file,
                                    @RequestParam(value = "accountId", required = false) String accountId,
                                    @RequestParam(value = "validationMode", required = false) String validationMode,
                                    @RequestParam(value = "completeness", required = false) String completeness,
                                    @RequestParam(value = "declaredFromDate", required = false) String declaredFromDate,
                                    @RequestParam(value = "declaredToDate", required = false) String declaredToDate);
}
