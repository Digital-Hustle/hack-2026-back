package ru.cashflow.preprocessor.features.statements.command;

import java.io.FilterInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;

import ru.cashflow.preprocessor.features.statements.model.CsvInputException;

final class GuardedCsvReader extends FilterReader {

    private final int fieldLimit;
    private final long deadline;
    private int fieldLength;
    private int columns = 1;
    private boolean fieldStart = true;
    private boolean quoted;
    private boolean pendingQuote;
    private long line = 1;

    private GuardedCsvReader(Reader reader, int fieldLimit, long deadline) {
        super(reader);
        this.fieldLimit = fieldLimit;
        this.deadline = deadline;
    }

    static Reader open(InputStream stream, long bytesLimit, int fieldLimit, long deadline, MessageDigest digest)
            throws IOException {
        InputStream bounded = new FilterInputStream(new DigestInputStream(stream, digest)) {
            private long bytes;

            private void count(int read) {
                if (read > 0) {
                    bytes += read;
                    if (bytes > bytesLimit) {
                        throw new CsvInputException("FILE_TOO_LARGE", 413, "CSV превышает допустимый размер", 0);
                    }
                }
            }

            @Override
            public int read() throws IOException {
                int result = in.read();
                count(result == -1 ? 0 : 1);
                return result;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int result = in.read(buffer, offset, (int) Math.min(length, bytesLimit - bytes + 1));
                count(result);
                return result;
            }
        };
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var reader = new PushbackReader(new InputStreamReader(bounded, decoder), 1);
        int first = reader.read();
        if (first != -1 && first != '\uFEFF') {
            reader.unread(first);
        }
        return new GuardedCsvReader(reader, fieldLimit, deadline);
    }

    @Override
    public int read() throws IOException {
        int result = super.read();
        if (result != -1) {
            inspect((char) result);
        }
        return result;
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        int result = super.read(buffer, offset, length);
        for (int i = offset; i < offset + Math.max(0, result); i++) {
            inspect(buffer[i]);
        }
        return result;
    }

    private void inspect(char character) {
        if (System.nanoTime() - deadline > 0) {
            throw new CsvInputException("PROCESSING_TIMEOUT", 422, "Превышено время обработки CSV", line);
        }
        if (character == '\0') {
            throw new CsvInputException("INVALID_CHARACTER", 422, "CSV содержит недопустимый нулевой символ", line);
        }
        if (character == '\n') {
            line++;
        }
        if (quoted && pendingQuote && character != '"') {
            quoted = false;
            pendingQuote = false;
        }
        if (quoted) {
            if (character == '"') {
                pendingQuote = !pendingQuote;
            }
            incrementField();
        } else if (character == ';' || character == '\n' || character == '\r') {
            fieldLength = 0;
            fieldStart = true;
            columns = character == ';' ? columns + 1 : 1;
            if (columns > 64) {
                throw new CsvInputException("TOO_MANY_COLUMNS", 422, "Допускается не более 64 колонок CSV", line);
            }
        } else {
            if (fieldStart && character == '"') {
                quoted = true;
            }
            fieldStart = false;
            incrementField();
        }
    }

    private void incrementField() {
        fieldLength++;
        if (fieldLength > fieldLimit * 2 + 2) {
            throw new CsvInputException("FIELD_TOO_LONG", 422, "Поле CSV превышает допустимую длину", line);
        }
    }
}
