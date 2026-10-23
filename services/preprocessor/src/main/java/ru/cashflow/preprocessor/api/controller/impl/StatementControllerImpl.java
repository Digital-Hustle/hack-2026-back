package ru.cashflow.preprocessor.api.controller.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ru.cashflow.preprocessor.api.controller.StatementController;
import ru.cashflow.preprocessor.api.dto.response.PreparedStatementRs;
import ru.cashflow.preprocessor.api.mapper.StatementMapper;
import ru.cashflow.preprocessor.config.ParseLimits;
import ru.cashflow.preprocessor.features.statements.application.StatementPreparationService;
import ru.cashflow.preprocessor.features.statements.model.CsvInputException;

@RestController
public class StatementControllerImpl implements StatementController {

    private final StatementPreparationService service;
    private final StatementMapper mapper;
    private final ParseLimits limits;

    public StatementControllerImpl(StatementPreparationService service, StatementMapper mapper, ParseLimits limits) {
        this.service = service;
        this.mapper = mapper;
        this.limits = limits;
    }

    @Override
    public PreparedStatementRs prepare(MultipartFile file, MultipartFile companies) {
        validate(file, limits.statementBytes());
        if (companies != null) {
            validate(companies, limits.companyBytes());
        }
        try (InputStream statementStream = file.getInputStream();
             InputStream companyStream = companies == null ? null : companies.getInputStream()) {
            return mapper.response(service.prepare(statementStream, companyStream));
        } catch (IOException exception) {
            throw new CsvInputException("FILE_READ_ERROR", 422, "Не удалось прочитать загруженный CSV", 0);
        }
    }

    private void validate(MultipartFile file, long maxBytes) {
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new CsvInputException("UNSUPPORTED_FILE", 415, "Поддерживаются только файлы CSV", 0);
        }
        if (file.isEmpty()) {
            throw new CsvInputException("EMPTY_FILE", 422, "CSV пуст", 0);
        }
        if (file.getSize() > maxBytes) {
            throw new CsvInputException("FILE_TOO_LARGE", 413, "CSV превышает допустимый размер", 0);
        }
    }
}
