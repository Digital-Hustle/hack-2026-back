package ru.cashflow.preprocessor.units;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import ru.cashflow.preprocessor.config.ParseLimits;
import ru.cashflow.preprocessor.features.statements.command.CsvStatementParser;
import ru.cashflow.preprocessor.features.statements.command.RuleCategorizer;
import ru.cashflow.preprocessor.features.statements.model.CsvInputException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcelStatementParserTest {

    private static final String[] HEADERS = {
        "company_id", "operation_id", "operation_date", "amount", "direction", "counterparty_inn",
        "counterparty_name", "payment_purpose", "balance_eod"
    };
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private final CsvStatementParser parser = new CsvStatementParser(CLOCK, ParseLimits.defaults(),
            new RuleCategorizer());

    @Test
    void readsXlsxWithTypedMoneyDatesAndLeadingZeroTextIdentifiers() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Operations");
            var header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
            }
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("C001");
            row.createCell(1).setCellValue("A");
            var date = row.createCell(2);
            date.setCellValue(java.sql.Date.valueOf("2026-09-30"));
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            date.setCellStyle(dateStyle);
            row.createCell(3).setCellValue(123.45);
            row.createCell(4).setCellValue("Поступление");
            row.createCell(5).setCellValue("0012345678");
            row.createCell(6).setCellValue("Клиент");
            row.createCell(7).setCellValue("Оплата по счету");
            row.createCell(8).setCellValue(900.55);
            var result = parser.parse(stream(workbook), "operations.xlsx", null, null);

            assertThat(result.operations()).hasSize(1);
            var operation = result.operations().getFirst();
            assertThat(operation.counterpartyInn()).isEqualTo("0012345678");
            assertThat(operation.amount()).hasToString("123.45");
            assertThat(operation.balanceEod()).hasToString("900.55");
            assertThat(operation.operationDate()).hasToString("2026-09-30");
            assertThat(operation.sourceLine()).isEqualTo(2);
            assertThat(result.report().rowsAccepted()).isEqualTo(1);
        }
    }

    @Test
    void readsLegacyXlsWithSameBusinessRules() throws Exception {
        try (Workbook workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet();
            var header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
            }
            var row = sheet.createRow(1);
            String[] values = {"C001", "A", "2026-09-30", "10.00", "Списание",
                    "", "Банк", "Комиссия за ведение счета", "100.00"};
            for (int i = 0; i < values.length; i++) {
                row.createCell(i).setCellValue(values[i]);
            }
            var result = parser.parse(stream(workbook), "operations.xls", null, null);
            assertThat(result.operations()).hasSize(1);
            assertThat(result.operations().getFirst().category().code().name()).isEqualTo("BANK_FEES");
        }
    }

    @Test
    void acceptsMultipleSheetsAndCachedFormulaResults() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            for (int number = 0; number < 2; number++) {
                var sheet = workbook.createSheet();
                var header = sheet.createRow(0);
                for (int i = 0; i < HEADERS.length; i++) {
                    header.createCell(i).setCellValue(HEADERS[i]);
                }
                var row = sheet.createRow(1);
                String[] values = {"C001", "A" + number, "2026-09-30", "10.00", "Списание",
                        "", "Банк", "Комиссия за ведение счета", "100.00"};
                for (int i = 0; i < values.length; i++) {
                    row.createCell(i).setCellValue(values[i]);
                }
                row.getCell(3).setCellFormula("5+5");
                workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(row.getCell(3));
            }
            var result = parser.parse(stream(workbook), "file.xlsx", null, null);
            assertThat(result.operations()).hasSize(2);
            assertThat(result.operations()).allSatisfy(operation ->
                    assertThat(operation.amount()).hasToString("10.00"));
        }
    }

    @Test
    void rejectsDamagedWorkbook() {
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(new byte[] {1, 2, 3}),
                        "file.xlsx", null, null))
                .isInstanceOf(CsvInputException.class);
    }

    private static ByteArrayInputStream stream(Workbook workbook) throws Exception {
        var output = new ByteArrayOutputStream();
        workbook.write(output);
        return new ByteArrayInputStream(output.toByteArray());
    }
}
