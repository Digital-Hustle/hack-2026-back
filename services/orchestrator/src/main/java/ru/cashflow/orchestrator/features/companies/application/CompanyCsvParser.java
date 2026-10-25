package ru.cashflow.orchestrator.features.companies.application;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.command.RussianTaxIds;
import ru.cashflow.orchestrator.features.companies.model.CompanyImportRow;
import ru.cashflow.orchestrator.features.companies.model.LegalForm;

@Component
public class CompanyCsvParser {

    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ROWS = 2_000;
    private static final List<String> HEADER = List.of("company_id", "company_name", "company_inn", "legal_form",
            "industry", "first_operation_date", "last_operation_date", "operations_count");

    public List<CompanyImportRow> parse(byte[] content) {
        if (content == null || content.length == 0) {
            throw invalid("Файл пуст");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new ApiException(413, "COMPANIES_FILE_TOO_LARGE", "Максимальный размер CSV — 2 МБ");
        }

        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(content), decoder))) {
            reader.mark(1);
            if (reader.read() != '\uFEFF') {
                reader.reset();
            }
            var format = CSVFormat.DEFAULT.builder().setDelimiter(';').setHeader()
                    .setSkipHeaderRecord(true).setIgnoreEmptyLines(true).get();
            try (var parser = format.parse(reader)) {
                if (!parser.getHeaderNames().equals(HEADER)) {
                    throw invalid("Неверные заголовки или порядок колонок CSV");
                }

                List<CompanyImportRow> rows = new ArrayList<>();
                Set<String> ids = new HashSet<>();
                for (CSVRecord record : parser) {
                    if (rows.size() >= MAX_ROWS) {
                        throw invalid("Максимум 2000 компаний в одном файле");
                    }
                    long line = record.getRecordNumber() + 1;
                    if (!record.isConsistent() || record.size() != HEADER.size()) {
                        throw invalidRow(line, "ожидается восемь колонок");
                    }
                    CompanyImportRow row = readRow(record, line);
                    if (!ids.add(row.externalId())) {
                        throw invalidRow(line, "повторяется company_id");
                    }
                    rows.add(row);
                }
                if (rows.isEmpty()) {
                    throw invalid("Файл не содержит компаний");
                }
                return rows;
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid("Некорректный CSV или кодировка (ожидается UTF-8)");
        }
    }

    private static CompanyImportRow readRow(CSVRecord record, long line) {
        String externalId = record.get(0).strip();
        String name = record.get(1).strip();
        String inn = record.get(2).strip();
        String form = record.get(3).strip();
        String industry = record.get(4).strip();
        if (!externalId.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw invalidRow(line, "некорректный company_id");
        }
        if (name.isEmpty() || name.length() > 500) {
            throw invalidRow(line, "company_name должен содержать 1–500 символов");
        }
        LegalForm legalForm = switch (form) {
            case "ООО", "OOO" -> LegalForm.OOO;
            case "ИП", "IP" -> LegalForm.IP;
            default -> throw invalidRow(line, "неизвестная legal_form");
        };
        if (!RussianTaxIds.isValidCompanyInn(inn, legalForm)) {
            throw invalidRow(line, "company_inn не прошёл проверку контрольной суммы или не соответствует legal_form");
        }
        if (industry.isEmpty() || industry.length() > 200) {
            throw invalidRow(line, "industry должен содержать 1–200 символов");
        }
        try {
            LocalDate first = LocalDate.parse(record.get(5).strip());
            LocalDate last = LocalDate.parse(record.get(6).strip());
            if (first.isAfter(last)) {
                throw invalidRow(line, "first_operation_date позже last_operation_date");
            }
            int count = Integer.parseInt(record.get(7).strip());
            if (count < 0) {
                throw invalidRow(line, "operations_count не может быть отрицательным");
            }
            return new CompanyImportRow(externalId, name, inn, legalForm, industry, first, last, count);
        } catch (DateTimeParseException | NumberFormatException exception) {
            throw invalidRow(line, "некорректная дата или operations_count");
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("INVALID_COMPANIES_CSV", message);
    }

    private static ApiException invalidRow(long line, String message) {
        return invalid("Строка " + line + ": " + message);
    }
}
