package ru.cashflow.preprocessor.api.controller.impl;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ru.cashflow.preprocessor.api.controller.StatementController;
import ru.cashflow.preprocessor.api.dto.response.PreparedStatementRs;
import ru.cashflow.preprocessor.api.mapper.StatementMapper;
import ru.cashflow.preprocessor.api.validation.StatementFileValidator;
import ru.cashflow.preprocessor.config.ParseLimits;
import ru.cashflow.preprocessor.features.statements.application.StatementPreparationService;
import ru.cashflow.preprocessor.features.statements.model.CsvInputException;

@RestController
public class StatementControllerImpl implements StatementController {

    private final StatementPreparationService service;
    private final StatementMapper mapper;
    private final StatementFileValidator fileValidator;
    private final ParseLimits limits;

    public StatementControllerImpl(StatementPreparationService service, StatementMapper mapper,
                                   StatementFileValidator fileValidator, ParseLimits limits) {
        this.service = service;
        this.mapper = mapper;
        this.fileValidator = fileValidator;
        this.limits = limits;
    }

    @Override
    public PreparedStatementRs prepare(MultipartFile file, MultipartFile companies) {
        fileValidator.validate(file, limits.statementBytes());
        if (companies != null) {
            fileValidator.validate(companies, limits.companyBytes());
        }
        try (InputStream statementStream = file.getInputStream();
             InputStream companyStream = companies == null ? null : companies.getInputStream()) {
            return mapper.response(service.prepare(statementStream, file.getOriginalFilename(), companyStream,
                    companies == null ? null : companies.getOriginalFilename()));
        } catch (IOException exception) {
            throw new CsvInputException("FILE_READ_ERROR", 422, "Не удалось прочитать загруженный файл", 0);
        }
    }
}
