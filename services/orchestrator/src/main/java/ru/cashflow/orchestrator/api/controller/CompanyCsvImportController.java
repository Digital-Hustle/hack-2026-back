package ru.cashflow.orchestrator.api.controller;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.companies.application.CompanyCsvImportService;

/**
 * Unlike bank statement imports, this endpoint creates company identities only:
 * it never creates accounts, payments or forecasts.
 */
@RestController
@RequestMapping("/api/v1/orchestrator/companies")
public class CompanyCsvImportController {

    private final CompanyCsvImportService service;

    public CompanyCsvImportController(CompanyCsvImportService service) {
        this.service = service;
    }

    @PostMapping(path = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CompanyCsvImportService.ImportResult> upload(CurrentUser user,
                                                                       @RequestPart("file") MultipartFile file) {
        try {
            var result = service.importCompanies(user.userId(), file.getBytes());
            return ResponseEntity.status(HttpStatus.OK).body(result);
        } catch (IOException exception) {
            throw ApiException.unprocessable("INVALID_COMPANIES_CSV", "Не удалось прочитать файл");
        }
    }
}
