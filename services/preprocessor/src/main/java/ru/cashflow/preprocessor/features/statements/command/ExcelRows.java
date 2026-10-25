package ru.cashflow.preprocessor.features.statements.command;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import ru.cashflow.preprocessor.config.ParseLimits;
import ru.cashflow.preprocessor.features.statements.model.CsvInputException;

/**
 * Shared bounded row collector for the streaming XLS/XLSX readers. The compressed upload is spooled
 * to disk; only the current row, sheet headers and Apache POI's shared-string dictionary are retained.
 */
final class ExcelRows {

    private static final int MAX_COLUMNS = 64;
    private static final int BUFFER_SIZE = 8192;
    private static final int EXCEL_NUMERIC_DIGITS = 15;

    private ExcelRows() {}

    static void read(InputStream stream, String filename, String source, List<String> required, long byteLimit,
                     ParseLimits limits, long deadline, MessageDigest digest, Consumer<CsvRows.Row> consumer) {
        String suffix = filename.toLowerCase(Locale.ROOT).endsWith(".xlsx") ? ".xlsx" : ".xls";
        Path temporary = null;
        try {
            temporary = Files.createTempFile("preprocessor-excel-", suffix);
            try (OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long total = 0;
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    checkDeadline(deadline, 0);
                    total += read;
                    if (total > byteLimit) {
                        throw problem("FILE_TOO_LARGE", 413, "Excel превышает допустимый размер", 0);
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            var context = new Context(source, required, limits, deadline, consumer);
            if (suffix.equals(".xlsx")) {
                XlsxStreamingRows.read(temporary, context);
            } else {
                XlsStreamingRows.read(temporary, context);
            }
        } catch (CsvInputException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw problem("INVALID_EXCEL", 422, "Не удалось прочитать Excel; проверьте формат и целостность файла", 0);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // An operating system may defer temporary-file removal until its handle is closed.
                }
            }
        }
    }

    static String number(String raw, long line) {
        try {
            BigDecimal decimal = new BigDecimal(raw).stripTrailingZeros();
            if (decimal.precision() > EXCEL_NUMERIC_DIGITS) {
                throw problem("NUMERIC_PRECISION", 422,
                        "Число Excel содержит более 15 значащих цифр; сохраните значение как текст", line);
            }
            return decimal.toPlainString();
        } catch (NumberFormatException exception) {
            throw problem("INVALID_CELL", 422, "Некорректное число Excel", line);
        }
    }

    static void checkDeadline(long deadline, long line) {
        if (System.nanoTime() - deadline > 0) {
            throw problem("PROCESSING_TIMEOUT", 422, "Превышено время обработки Excel", line);
        }
    }

    static CsvInputException problem(String code, int status, String message, long line) {
        return new CsvInputException(code, status, message, line);
    }

    static final class Context {
        private final String source;
        private final List<String> required;
        private final ParseLimits limits;
        private final long deadline;
        private final Consumer<CsvRows.Row> consumer;
        private long recordCount;

        Context(String source, List<String> required, ParseLimits limits, long deadline,
                Consumer<CsvRows.Row> consumer) {
            this.source = source;
            this.required = required;
            this.limits = limits;
            this.deadline = deadline;
            this.consumer = consumer;
        }

        Sheet sheet(String name) {
            return new Sheet(this, name);
        }
    }

    static final class Sheet {
        private final Context context;
        private final String name;
        private Map<String, Integer> headers;
        private final Map<Integer, String> fields = new HashMap<>();
        private int currentRow = -1;

        Sheet(Context context, String name) {
            this.context = context;
            this.name = name;
        }

        void cell(int row, int column, String value) {
            checkDeadline(context.deadline, row + 1L);
            if (row < 0 || column < 0 || column >= MAX_COLUMNS) {
                throw problem("TOO_MANY_COLUMNS", 422, "Excel содержит более 64 колонок", row + 1L);
            }
            if (currentRow != row) {
                finishRow();
                currentRow = row;
            }
            if (value.length() > context.limits.maxFieldLength()) {
                throw problem("FIELD_TOO_LONG", 422, "Поле Excel превышает допустимую длину", row + 1L);
            }
            fields.put(column, value);
        }

        void finishRow() {
            if (currentRow < 0) {
                return;
            }
            int line = currentRow + 1;
            checkDeadline(context.deadline, line);
            if (fields.values().stream().allMatch(String::isBlank)) {
                fields.clear();
                return;
            }
            if (currentRow == 0) {
                headers = new LinkedHashMap<>();
                int lastColumn = fields.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
                for (int i = 0; i <= lastColumn; i++) {
                    String header = fields.getOrDefault(i, "").strip().toLowerCase(Locale.ROOT);
                    if (header.isEmpty() || headers.putIfAbsent(header, i) != null) {
                        throw problem("INVALID_HEADERS", 422,
                                "Заголовки Excel пусты или повторяются на листе " + name, line);
                    }
                }
                if (!headers.keySet().containsAll(context.required)) {
                    throw problem("MISSING_COLUMNS", 422,
                            "Не найдены обязательные колонки Excel на листе " + name, line);
                }
            } else {
                if (headers == null) {
                    throw problem("MISSING_COLUMNS", 422,
                            "Заголовки Excel должны находиться в первой строке листа " + name, line);
                }
                for (var entry : fields.entrySet()) {
                    if (entry.getKey() >= headers.size() && !entry.getValue().isBlank()) {
                        throw problem("INCONSISTENT_COLUMNS", 422,
                                "Строка содержит значения за пределами заголовков", line);
                    }
                }
                if (++context.recordCount > context.limits.maxRows()) {
                    throw problem("TOO_MANY_ROWS", 413, "Превышено допустимое число строк Excel", line);
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (var header : headers.entrySet()) {
                    row.put(header.getKey(), fields.getOrDefault(header.getValue(), ""));
                }
                context.consumer.accept(new CsvRows.Row(context.source, context.recordCount, line, Map.copyOf(row)));
            }
            fields.clear();
        }

        void finishSheet() {
            finishRow();
        }
    }
}
