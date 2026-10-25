package ru.cashflow.orchestrator.features.imports.application;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import ru.cashflow.orchestrator.features.imports.model.RawOperation;
import ru.cashflow.orchestrator.features.ledger.model.LedgerTypes.Direction;

/** Reads one source company at a time. The original 64 MB file is never materialized as a String or row list. */
@Component
public class RawOperationsCsvParser {

    public static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    private static final int MAX_ROWS = 300_000;
    private static final int MAX_COMPANIES = 500;
    private static final List<String> HEADERS = List.of("company_id", "operation_id", "operation_date", "amount",
            "direction", "counterparty_inn", "counterparty_name", "payment_purpose", "balance_eod");

    @FunctionalInterface
    public interface GroupConsumer {
        void accept(String sourceCompanyId, List<RawOperation> rows);
    }

    public void parse(InputStream input, GroupConsumer consumer) {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new BufferedReader(new InputStreamReader(input, decoder))) {
            reader.mark(1);
            if (reader.read() != '\uFEFF') {
                reader.reset();
            }
            var format = CSVFormat.DEFAULT.builder().setDelimiter(';').setHeader()
                    .setSkipHeaderRecord(true).setIgnoreEmptyLines(true).get();
            try (var parser = format.parse(reader)) {
                if (!HEADERS.equals(parser.getHeaderNames())) {
                    throw invalid("Неверные заголовки или порядок колонок CSV");
                }
                List<RawOperation> group = new ArrayList<>();
                Set<String> operationIds = new HashSet<>();
                Set<String> companies = new HashSet<>();
                String current = null;
                int total = 0;
                for (CSVRecord record : parser) {
                    if (++total > MAX_ROWS) {
                        throw invalid("Превышен лимит 300000 операций");
                    }
                    long line = record.getRecordNumber() + 1;
                    if (!record.isConsistent() || record.size() != HEADERS.size()) {
                        throw invalidRow(line, "ожидается девять колонок");
                    }
                    RawOperation operation = row(record, line);
                    if (!operation.sourceCompanyId().equals(current)) {
                        if (current != null) {
                            consumer.accept(current, List.copyOf(group));
                            group.clear();
                            operationIds.clear();
                        }
                        current = operation.sourceCompanyId();
                        if (!companies.add(current)) {
                            throw invalidRow(line, "операции компании должны идти одним блоком");
                        }
                        if (companies.size() > MAX_COMPANIES) {
                            throw invalid("Превышен лимит 500 компаний");
                        }
                    }
                    if (!operationIds.add(operation.externalOperationId())) {
                        throw invalidRow(line, "повторяется operation_id");
                    }
                    group.add(operation);
                }
                if (total == 0) {
                    throw invalid("Файл не содержит операций");
                }
                consumer.accept(current, List.copyOf(group));
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid("Некорректный CSV или кодировка (ожидается UTF-8)");
        }
    }

    private static RawOperation row(CSVRecord record, long line) {
        String companyId = record.get(0).strip();
        String operationId = record.get(1).strip();
        if (!companyId.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw invalidRow(line, "некорректный company_id");
        }
        if (!operationId.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw invalidRow(line, "некорректный operation_id");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(record.get(2).strip());
        } catch (DateTimeParseException exception) {
            throw invalidRow(line, "некорректная operation_date");
        }
        BigDecimal amount = money(record.get(3), line, "amount");
        if (amount.signum() <= 0) {
            throw invalidRow(line, "amount должен быть положительным");
        }
        Direction direction = switch (record.get(4).strip()) {
            case "Поступление" -> Direction.IN;
            case "Списание" -> Direction.OUT;
            default -> throw invalidRow(line, "неизвестное direction");
        };
        String inn = blankToNull(record.get(5));
        if (inn != null && !inn.matches("(?:[0-9]{10}|[0-9]{12})")) {
            throw invalidRow(line, "некорректный counterparty_inn");
        }
        String counterpartyName = blankToNull(record.get(6));
        if (counterpartyName != null && counterpartyName.length() > 500) {
            throw invalidRow(line, "слишком длинный counterparty_name");
        }
        String purpose = blankToNull(record.get(7));
        if (purpose != null && purpose.length() > 2000) {
            throw invalidRow(line, "слишком длинный payment_purpose");
        }
        BigDecimal balance = money(record.get(8), line, "balance_eod");
        return new RawOperation(companyId, operationId, date, amount, direction, inn, counterpartyName,
                purpose, balance);
    }

    private static BigDecimal money(String value, long line, String field) {
        try {
            BigDecimal parsed = new BigDecimal(value.strip());
            // PostgreSQL numeric(19,2): no silent rounding and no floating-point conversions.
            if (parsed.scale() > 2 || parsed.setScale(2, RoundingMode.UNNECESSARY).precision() > 19) {
                throw new ArithmeticException();
            }
            return parsed.setScale(2, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalidRow(line, "некорректное денежное поле " + field);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException invalid(String message) {
        return ApiException.unprocessable("INVALID_OPERATIONS_CSV", message);
    }

    private static ApiException invalidRow(long line, String message) {
        return invalid("Строка " + line + ": " + message);
    }
}
