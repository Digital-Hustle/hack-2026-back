package ru.cashflow.orchestrator.api.controller;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.api.security.CurrentUser;
import ru.cashflow.orchestrator.features.imports.application.RawOperationsCsvParser;
import ru.cashflow.orchestrator.features.imports.application.RawOperationsImportService;

@RestController
@RequestMapping("/api/v1/orchestrator/companies")
public class RawOperationsCsvController {

    private final RawOperationsImportService service;

    public RawOperationsCsvController(RawOperationsImportService service) {
        this.service = service;
    }

    @PostMapping(path = "/operations/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RawOperationsImportService.Result upload(CurrentUser user,
                                                     @RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw ApiException.unprocessable("INVALID_OPERATIONS_CSV", "Файл пуст");
        }
        if (file.getSize() > RawOperationsCsvParser.MAX_FILE_BYTES) {
            throw new ApiException(413, "OPERATIONS_FILE_TOO_LARGE", "Максимальный размер CSV — 100 МБ");
        }
        try (var input = file.getInputStream()) {
            return service.importFile(user.userId(), input);
        } catch (IOException exception) {
            throw ApiException.unprocessable("INVALID_OPERATIONS_CSV", "Не удалось прочитать CSV");
        }
    }
}
