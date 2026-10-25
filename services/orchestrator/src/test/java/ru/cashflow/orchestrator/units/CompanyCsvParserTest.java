package ru.cashflow.orchestrator.units;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.api.error.ApiException;
import ru.cashflow.orchestrator.features.companies.application.CompanyCsvParser;
import ru.cashflow.orchestrator.features.companies.model.LegalForm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompanyCsvParserTest {

    private final CompanyCsvParser parser = new CompanyCsvParser();

    private static final String HEADER = "company_id;company_name;company_inn;legal_form;industry;"
            + "first_operation_date;last_operation_date;operations_count\n";
    private static final String ROW = "C001;ООО «Тест»;0098524470;ООО;Торговля;2024-10-01;2026-09-29;1131\n";

    @Test
    void acceptsUtf8BomCrLfAndPreservesLeadingZeros() {
        byte[] content = ("\uFEFF" + (HEADER + ROW).replace("\n", "\r\n"))
                .getBytes(StandardCharsets.UTF_8);
        var rows = parser.parse(content);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().inn()).isEqualTo("0098524470");
        assertThat(rows.getFirst().legalForm()).isEqualTo(LegalForm.OOO);
        assertThat(rows.getFirst().operationsCount()).isEqualTo(1131);
    }

    @Test
    void acceptsQuotedSemicolonAndIndividualEntrepreneur() {
        var rows = parser.parse((HEADER
                + "C002;\"ИП; Тест\";003844671674;ИП;Услуги;2024-10-01;2026-09-30;1\n")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(rows.getFirst().name()).isEqualTo("ИП; Тест");
        assertThat(rows.getFirst().legalForm()).isEqualTo(LegalForm.IP);
    }

    @Test
    void rejectsRepeatedIdsInvalidRowsAndBadEncoding() {
        assertThatThrownBy(() -> parser.parse((HEADER + ROW + ROW).getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class).hasMessageContaining("company_id");
        assertThatThrownBy(() -> parser.parse((HEADER + ROW.replace("1131", "-1"))
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parser.parse((HEADER + ROW.replace("2026-09-29", "2023-09-29"))
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parser.parse(new byte[]{(byte) 0xFF}))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parser.parse((HEADER.replace("industry", "sector") + ROW)
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parser.parse((HEADER + ROW.replace("0098524470", "0098524471"))
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(ApiException.class)
                .hasMessageContaining("company_inn");
        assertThatThrownBy(() -> parser.parse((HEADER + ROW.replace(";ООО;", ";ИП;"))
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(ApiException.class)
                .hasMessageContaining("company_inn");
    }

    @Test
    void rejectsEmptyAndOversizedFiles() {
        assertThatThrownBy(() -> parser.parse(new byte[0])).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parser.parse(new byte[CompanyCsvParser.MAX_FILE_BYTES + 1]))
                .isInstanceOf(ApiException.class);
    }
}
