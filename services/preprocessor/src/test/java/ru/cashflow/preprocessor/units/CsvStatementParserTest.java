package ru.cashflow.preprocessor.units;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import ru.cashflow.preprocessor.config.ParseLimits;
import ru.cashflow.preprocessor.features.statements.command.CsvStatementParser;
import ru.cashflow.preprocessor.features.statements.command.RuleCategorizer;
import ru.cashflow.preprocessor.features.statements.model.CategoryCode;
import ru.cashflow.preprocessor.features.statements.model.CsvInputException;
import ru.cashflow.preprocessor.features.statements.model.Direction;
import ru.cashflow.preprocessor.features.statements.model.StatementResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvStatementParserTest {

    static final String HEADER = "company_id;operation_id;operation_date;amount;direction;counterparty_inn;"
            + "counterparty_name;payment_purpose;balance_eod\n";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private final CsvStatementParser parser = new CsvStatementParser(CLOCK, ParseLimits.defaults(), new RuleCategorizer());

    @Test
    void preservesAllCsvFieldsAndLeadingZerosWithUtf8Bom() {
        var result = parse("\uFEFF" + HEADER
                + "C001;C001-1;2026-09-30;990.00;Списание;0012345678;Банк;Комиссия за ведение счета;1000.10\n");

        assertThat(result.operations()).hasSize(1);
        var operation = result.operations().getFirst();
        assertThat(operation.companyId()).isEqualTo("C001");
        assertThat(operation.operationId()).isEqualTo("C001-1");
        assertThat(operation.amount()).hasToString("990.00");
        assertThat(operation.balanceEod()).hasToString("1000.10");
        assertThat(operation.counterpartyInn()).isEqualTo("0012345678");
        assertThat(operation.direction()).isEqualTo(Direction.OUT);
        assertThat(operation.category().code()).isEqualTo(CategoryCode.BANK_FEES);
        assertThat(operation.sourceLine()).isEqualTo(2);
        assertThat(operation.sourceFields()).containsEntry("direction", "Списание");
        assertThat(result.report().rowsAccepted()).isEqualTo(1);
        assertThat(result.report().status()).isEqualTo("PREPARED");
    }

    @Test
    void handlesQuotedSemicolonsNewlinesAndAdditionalColumnsWithoutLosingValues() {
        var result = parse(HEADER.stripTrailing() + ";bank_note\n"
                + "C001;C001-1;2026-09-30;100.00;Поступление;;\"ООО; Клиент\";\"Оплата по счету\nза услуги\";100.00;extra\n");

        assertThat(result.operations()).hasSize(1);
        var operation = result.operations().getFirst();
        assertThat(operation.counterpartyName()).isEqualTo("ООО; Клиент");
        assertThat(operation.paymentPurpose()).contains("\n");
        assertThat(operation.sourceFields()).containsEntry("bank_note", "extra");
        assertThat(result.report().issueCounts()).containsKey("EMPTY_COUNTERPARTY_INN");
    }

    @ParameterizedTest
    @MethodSource("invalidRows")
    void rejectsInvalidRowsWithFieldAndLineInsteadOfInventingValues(String row, String field) {
        var result = parse(HEADER + row + "\n");

        assertThat(result.operations()).isEmpty();
        assertThat(result.report().rowsTotal()).isEqualTo(1);
        assertThat(result.report().rowsRejected()).isEqualTo(1);
        assertThat(result.report().status()).isEqualTo("INVALID");
        assertThat(result.report().issues()).anySatisfy(issue -> {
            assertThat(issue.field()).isEqualTo(field);
            assertThat(issue.sourceLine()).isEqualTo(2);
            assertThat(issue.severity()).isEqualTo("ERROR");
        });
    }

    static Stream<Arguments> invalidRows() {
        return Stream.of(
                Arguments.of(";A;2026-09-30;1.00;Списание;;Банк;Текст;0.00", "company_id"),
                Arguments.of("C001;;2026-09-30;1.00;Списание;;Банк;Текст;0.00", "operation_id"),
                Arguments.of("C001;A;2026-02-30;1.00;Списание;;Банк;Текст;0.00", "operation_date"),
                Arguments.of("C001;A;0000-01-01;1.00;Списание;;Банк;Текст;0.00", "operation_date"),
                Arguments.of("C001;A;2026-10-25;1.00;Списание;;Банк;Текст;0.00", "operation_date"),
                Arguments.of("C001;A;2026-09-30;-1.00;Списание;;Банк;Текст;0.00", "amount"),
                Arguments.of("C001;A;2026-09-30;0.00;Списание;;Банк;Текст;0.00", "amount"),
                Arguments.of("C001;A;2026-09-30;1.001;Списание;;Банк;Текст;0.00", "amount"),
                Arguments.of("C001;A;2026-09-30;NaN;Списание;;Банк;Текст;0.00", "amount"),
                Arguments.of("C001;A;2026-09-30;1e6;Списание;;Банк;Текст;0.00", "amount"),
                Arguments.of("C001;A;2026-09-30;100000000000000000.00;Списание;;Банк;Текст;0.00", "amount"),
                Arguments.of("C001;A;2026-09-30;1.00;Другое;;Банк;Текст;0.00", "direction"),
                Arguments.of("C001;A;2026-09-30;1.00;Списание;12345;Банк;Текст;0.00", "counterparty_inn"),
                Arguments.of("C001;A;2026-09-30;1.00;Списание;;Банк;Текст;bad", "balance_eod")
        );
    }

    @Test
    void keepsValidRowsWhenAnotherRowIsInvalid() {
        var result = parse(HEADER
                + "C001;A;2026-09-30;1.00;Списание;;Банк;Текст;-10.00\n"
                + "C001;B;2026-09-30;bad;Списание;;Банк;Текст;-10.00\n");

        assertThat(result.operations()).hasSize(1);
        assertThat(result.operations().getFirst().balanceEod()).hasToString("-10.00");
        assertThat(result.report().rowsRejected()).isEqualTo(1);
        assertThat(result.report().status()).isEqualTo("PARTIAL");
    }

    @Test
    void missingOptionalValuesRemainMissingAndRequireReview() {
        var result = parse(HEADER + "C001;A;2026-09-30;1.00;Списание;;;;\n");

        assertThat(result.operations()).hasSize(1);
        assertThat(result.operations().getFirst().balanceEod()).isNull();
        assertThat(result.operations().getFirst().category().needsReview()).isTrue();
        assertThat(result.report().issueCounts()).containsKeys("EMPTY_PURPOSE", "MISSING_BALANCE");
        assertThat(result.dailyBalances().getFirst().balanceStatus()).isEqualTo("MISSING");
    }

    @Test
    void deduplicatesOnlyIdenticalExternalIdsNotLegitimateEqualPayments() {
        String row = "C001;A;2026-09-30;1.00;Списание;;Банк;Текст;10.00\n";
        var result = parse(HEADER + row + row + row.replace(";A;", ";B;"));

        assertThat(result.operations()).hasSize(2);
        assertThat(result.report().rowsTotal()).isEqualTo(3);
        assertThat(result.report().rowsDuplicate()).isEqualTo(1);
        assertThat(result.report().issueCounts()).containsKeys("DUPLICATE_OPERATION", "POSSIBLE_DUPLICATE");
    }

    @Test
    void conflictingExternalIdRejectsBothVersions() {
        String row = "C001;A;2026-09-30;1.00;Списание;;Банк;Текст;10.00\n";
        var result = parse(HEADER + row + row.replace(";1.00;", ";2.00;"));

        assertThat(result.operations()).isEmpty();
        assertThat(result.report().rowsRejected()).isEqualTo(2);
        assertThat(result.report().issueCounts()).containsKey("OPERATION_ID_CONFLICT");
    }

    @Test
    void aggregatesFlowsButDoesNotSumRepeatedEndOfDayBalances() {
        var result = parse(HEADER
                + "C001;A;2026-09-28;10.00;Поступление;;Клиент;Оплата;100.00\n"
                + "C001;B;2026-09-29;20.00;Поступление;;Клиент;Оплата;115.00\n"
                + "C001;C;2026-09-29;5.00;Списание;;Поставщик;Текст;115.00\n");

        assertThat(result.dailyBalances()).hasSize(2);
        var day = result.dailyBalances().get(1);
        assertThat(day.balanceEod()).hasToString("115.00");
        assertThat(day.inflow()).hasToString("20.00");
        assertThat(day.outflow()).hasToString("5.00");
        assertThat(day.reconcilesWithPrevious()).isTrue();
    }

    @Test
    void flagsConflictingDailyBalancesWithoutChoosingAnArbitraryAnchor() {
        var result = parse(HEADER
                + "C001;A;2026-09-30;10.00;Поступление;;Клиент;Оплата;100.00\n"
                + "C001;B;2026-09-30;5.00;Списание;;Банк;Текст;200.00\n");

        assertThat(result.dailyBalances().getFirst().balanceEod()).isNull();
        assertThat(result.dailyBalances().getFirst().balanceStatus()).isEqualTo("CONFLICT");
        assertThat(result.report().issueCounts()).containsKey("BALANCE_CONFLICT");
    }

    @Test
    void reportsMissingIntervalsAndMismatchesWithoutCreatingPaymentsOrDays() {
        var result = parse(HEADER
                + "C001;A;2026-09-01;10.00;Поступление;;Клиент;Оплата;100.00\n"
                + "C001;B;2026-09-15;10.00;Поступление;;Клиент;Оплата;500.00\n");

        assertThat(result.operations()).hasSize(2);
        assertThat(result.dailyBalances()).hasSize(2);
        assertThat(result.report().issueCounts()).containsKeys("HISTORY_GAP", "BALANCE_MISMATCH");
    }

    @Test
    void joinsOptionalCompanyMetadataAndRejectsUnknownCompanyReferences() {
        String companies = "company_id;company_name;company_inn;legal_form;industry;first_operation_date;"
                + "last_operation_date;operations_count\n"
                + "C001;Тестовая компания;0012345678;ООО;Услуги;2026-09-30;2026-09-30;1\n";
        var result = parser.parse(stream(HEADER
                + "C001;A;2026-09-30;10.00;Поступление;;Клиент;Оплата;100.00\n"
                + "C002;B;2026-09-30;10.00;Поступление;;Клиент;Оплата;100.00\n"), stream(companies));

        assertThat(result.companies()).hasSize(1);
        assertThat(result.companies().getFirst().companyInn()).isEqualTo("0012345678");
        assertThat(result.companies().getFirst().sourceFields()).containsEntry("industry", "Услуги");
        assertThat(result.operations()).hasSize(1);
        assertThat(result.report().issueCounts()).containsKey("UNKNOWN_COMPANY");
    }

    @Test
    void repeatedInputHasStableFingerprint() {
        String csv = HEADER + "C001;A;2026-09-30;10.00;Поступление;;Клиент;Оплата;100.00\n";

        assertThat(parse(csv).inputFingerprint()).hasSize(64).isEqualTo(parse(csv).inputFingerprint());
    }

    @Test
    void rejectsMissingDuplicateHeadersAndBrokenCsvStructure() {
        for (String csv : new String[] {"company_id;amount\nC001;1\n", HEADER.replace("amount;", "company_id;"),
                HEADER + "C001;A;2026-09-30;1.00;Списание;;Банк;\"unclosed;0.00\n"}) {
            assertThatThrownBy(() -> parse(csv)).isInstanceOf(CsvInputException.class);
        }
    }

    @Test
    void rejectsMalformedUtf8InsteadOfReplacingCharacters() {
        byte[] header = HEADER.getBytes(StandardCharsets.UTF_8);
        byte[] invalid = new byte[header.length + 2];
        System.arraycopy(header, 0, invalid, 0, header.length);
        invalid[header.length] = (byte) 0xC3;
        invalid[header.length + 1] = (byte) 0x28;

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(invalid), null))
                .isInstanceOf(CsvInputException.class);
    }

    @Test
    void enforcesByteRowAndIssueLimits() {
        String row = "C001;A;2026-09-30;1.00;Списание;;;;\n";
        var small = new CsvStatementParser(CLOCK, new ParseLimits(32, 32, 1, 4096, 2, 10), new RuleCategorizer());
        assertThatThrownBy(() -> small.parse(stream(HEADER + row), null)).isInstanceOf(CsvInputException.class);

        var oneRow = new CsvStatementParser(CLOCK, new ParseLimits(10_000, 10_000, 1, 4096, 2, 10), new RuleCategorizer());
        assertThatThrownBy(() -> oneRow.parse(stream(HEADER + row + row), null)).isInstanceOf(CsvInputException.class);

        var result = oneRow.parse(stream(HEADER + row), null);
        assertThat(result.report().issues()).hasSize(2);
        assertThat(result.report().issuesTruncated()).isTrue();
        assertThat(result.report().issueCount()).isGreaterThan(2);
    }

    @Test
    void reportsTheStartOfMultilineRecordsAndNormalizesHeaderNames() {
        String header = HEADER.replace("company_id", " COMPANY_ID ");
        var result = parse(header
                + "C001;A;2026-09-29;1.00;Списание;;\"Банк; отдел\";\"Оплата\nза услуги\";100.00\n"
                + "C001;B;2026-09-30;2.00;Списание;;Банк;Текст;98.00\n");

        assertThat(result.operations()).hasSize(2);
        assertThat(result.operations().get(0).sourceLine()).isEqualTo(2);
        assertThat(result.operations().get(1).sourceLine()).isEqualTo(4);
        assertThat(result.operations().get(1).sourceRecord()).isEqualTo(2);
    }

    @Test
    void rejectsExcessiveFieldsColumnsAndNullCharacters() {
        var limited = new CsvStatementParser(CLOCK, new ParseLimits(20_000, 20_000, 10, 100, 10, 10), new RuleCategorizer());
        assertThatThrownBy(() -> limited.parse(stream(HEADER
                + "C001;A;2026-09-30;1.00;Списание;;Банк;" + "x".repeat(300) + ";0.00\n"), null))
                .isInstanceOf(CsvInputException.class)
                .hasMessageContaining("длину");
        assertThatThrownBy(() -> parse(HEADER.stripTrailing() + ";extra".repeat(60) + "\n"))
                .isInstanceOf(CsvInputException.class);
        assertThatThrownBy(() -> parse(HEADER
                + "C001;A;2026-09-30;1.00;Списание;;Банк;bad\0text;0.00\n"))
                .isInstanceOf(CsvInputException.class);
    }

    @Test
    void reportsEncodingErrorsEvenWhenTheyOccurAfterTheInitialReaderBuffer() {
        byte[] valid = (HEADER + "C001;A;2026-09-30;1.00;Списание;;Банк;" + "x".repeat(1500)
                + ";0.00\n").getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[valid.length * 10 + 2];
        System.arraycopy(valid, 0, bytes, 0, valid.length);
        byte[] row = new String(valid, StandardCharsets.UTF_8).substring(HEADER.length()).getBytes(StandardCharsets.UTF_8);
        int length = valid.length;
        for (int i = 1; i < 10; i++) {
            System.arraycopy(row, 0, bytes, length, row.length);
            length += row.length;
        }
        bytes[length++] = (byte) 0xC3;
        bytes[length++] = (byte) 0x28;
        byte[] input = java.util.Arrays.copyOf(bytes, length);

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(input), null))
                .isInstanceOfSatisfying(CsvInputException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_ENCODING"));
    }

    @Test
    void reportsHeaderOnlyStatementAsEmptyInsteadOfAnUnexplainedInvalidResult() {
        var result = parse(HEADER);

        assertThat(result.report().status()).isEqualTo("INVALID");
        assertThat(result.report().rowsTotal()).isZero();
        assertThat(result.report().issueCounts()).containsKey("NO_OPERATIONS");
    }

    @Test
    void leavesDifferentCompaniesSeparateEvenWhenOperationIdsMatch() {
        var result = parse(HEADER
                + "C001;A;2026-09-30;1.00;Списание;;Банк;Текст;10.00\n"
                + "C002;A;2026-09-30;2.00;Списание;;Банк;Текст;20.00\n");

        assertThat(result.operations()).hasSize(2);
        assertThat(result.dailyBalances()).hasSize(2);
        assertThat(result.report().rowsDuplicate()).isZero();
        assertThat(result.dailyBalances().get(0).balanceEod()).hasToString("10.00");
        assertThat(result.dailyBalances().get(1).balanceEod()).hasToString("20.00");
    }

    @Test
    void conflictingIdAfterIdenticalDuplicatesAccountsForEveryRejectedRow() {
        String row = "C001;A;2026-09-30;1.00;Списание;;Банк;Текст;10.00\n";
        var result = parse(HEADER + row + row + row.replace(";1.00;", ";2.00;") + row);

        assertThat(result.operations()).isEmpty();
        assertThat(result.report().rowsTotal()).isEqualTo(4);
        assertThat(result.report().rowsRejected()).isEqualTo(4);
        assertThat(result.report().rowsDuplicate()).isZero();
    }

    @Test
    void rejectsInvalidLimitConfigurationBeforeProcessingAnyFile() {
        assertThatThrownBy(() -> new ParseLimits(-1, 100, 1, 100, 1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParseLimits(100, 100, 0, 100, 1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParseLimits(100, 100, 1, 100, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private StatementResult parse(String text) {
        return parser.parse(stream(text), null);
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
