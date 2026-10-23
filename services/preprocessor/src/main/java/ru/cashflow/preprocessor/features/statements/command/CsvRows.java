package ru.cashflow.preprocessor.features.statements.command;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

import ru.cashflow.preprocessor.config.ParseLimits;
import ru.cashflow.preprocessor.features.statements.model.CsvInputException;

final class CsvRows {

    private CsvRows() {}

    static void read(InputStream stream, String source, List<String> required, long bytesLimit, ParseLimits limits,
                     long deadline, MessageDigest digest, Consumer<Row> consumer) {
        long line = 1;
        try (Reader reader = GuardedCsvReader.open(stream, bytesLimit, limits.maxFieldLength(), deadline, digest);
             CSVParser csv = CSVFormat.DEFAULT.builder().setDelimiter(';').setHeader().setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(false).setAllowMissingColumnNames(false)
                     .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get().parse(reader)) {
            List<String> headers = csv.getHeaderNames();
            if (headers.isEmpty()) {
                throw new CsvInputException("EMPTY_FILE", 422, "CSV пуст: нужна строка заголовков", 1);
            }
            var normalizedHeaders = new LinkedHashMap<String, Integer>();
            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i).strip().toLowerCase(Locale.ROOT);
                if (header.isEmpty() || normalizedHeaders.putIfAbsent(header, i) != null) {
                    throw new CsvInputException("INVALID_HEADERS", 422, "Заголовки CSV пусты или повторяются", 1);
                }
            }
            if (!normalizedHeaders.keySet().containsAll(required)) {
                throw new CsvInputException("MISSING_COLUMNS", 422,
                        "Не найдены обязательные колонки; проверьте формат и разделитель CSV", 1);
            }
            line = csv.getCurrentLineNumber() + 1;
            for (CSVRecord record : csv) {
                long startLine = line;
                line = csv.getCurrentLineNumber() + 1;
                if (record.getRecordNumber() > limits.maxRows()) {
                    throw new CsvInputException("TOO_MANY_ROWS", 413, "Превышено допустимое число строк CSV", startLine);
                }
                if (!record.isConsistent()) {
                    throw new CsvInputException("INCONSISTENT_COLUMNS", 422,
                            "Число полей записи не соответствует заголовкам CSV", startLine);
                }
                Map<String, String> fields = new LinkedHashMap<>();
                for (var entry : normalizedHeaders.entrySet()) {
                    String value = record.get(entry.getValue());
                    if (value.length() > limits.maxFieldLength()) {
                        throw new CsvInputException("FIELD_TOO_LONG", 422, "Поле CSV превышает допустимую длину", startLine);
                    }
                    fields.put(entry.getKey(), value);
                }
                consumer.accept(new Row(source, record.getRecordNumber(), startLine, Map.copyOf(fields)));
            }
        } catch (CsvInputException exception) {
            throw exception;
        } catch (CharacterCodingException exception) {
            throw new CsvInputException("INVALID_ENCODING", 415, "Ожидается CSV в корректной кодировке UTF-8", line);
        } catch (UncheckedIOException exception) {
            if (exception.getCause() instanceof CharacterCodingException) {
                throw new CsvInputException("INVALID_ENCODING", 415, "Ожидается CSV в корректной кодировке UTF-8", line);
            }
            throw new CsvInputException("INVALID_CSV", 422, "Не удалось разобрать структуру CSV; проверьте файл", line);
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            throw new CsvInputException("INVALID_CSV", 422, "Не удалось разобрать структуру CSV; проверьте файл", line);
        }
    }

    record Row(String source, long record, long line, Map<String, String> fields) {
        String value(String field) {
            return fields.getOrDefault(field, "").strip();
        }
    }
}
