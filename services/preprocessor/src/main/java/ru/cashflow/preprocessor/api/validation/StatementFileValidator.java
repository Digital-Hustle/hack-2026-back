package ru.cashflow.preprocessor.api.validation;

import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import ru.cashflow.preprocessor.features.statements.model.CsvInputException;

/** Shared file checks for both the internal preparation endpoint and the public import endpoint. */
@Component
public class StatementFileValidator {

    public void validate(MultipartFile file, long maxBytes) {
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
