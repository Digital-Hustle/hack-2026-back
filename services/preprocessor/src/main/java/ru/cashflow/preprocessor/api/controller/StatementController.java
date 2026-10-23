package ru.cashflow.preprocessor.api.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import ru.cashflow.preprocessor.api.dto.response.PreparedStatementRs;

@RequestMapping("/internal/v1/statements")
public interface StatementController {

    @PostMapping(value = "/prepare", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    PreparedStatementRs prepare(@RequestPart("file") MultipartFile file,
                                @RequestPart(value = "companies", required = false) MultipartFile companies);
}
