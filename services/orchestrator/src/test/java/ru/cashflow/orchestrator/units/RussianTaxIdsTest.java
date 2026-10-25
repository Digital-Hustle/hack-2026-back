package ru.cashflow.orchestrator.units;

import org.junit.jupiter.api.Test;

import ru.cashflow.orchestrator.features.companies.command.RussianTaxIds;
import ru.cashflow.orchestrator.features.companies.model.LegalForm;

import static org.assertj.core.api.Assertions.assertThat;

class RussianTaxIdsTest {

    @Test
    void legalEntityInnUsesTenDigitsAndControlDigit() {
        assertThat(RussianTaxIds.isValidCompanyInn("7707083893", LegalForm.OOO)).isTrue();
        assertThat(RussianTaxIds.isValidCompanyInn("7707083894", LegalForm.OOO)).isFalse();
        assertThat(RussianTaxIds.isValidCompanyInn("770708389", LegalForm.OOO)).isFalse();
        assertThat(RussianTaxIds.isValidCompanyInn("500100732259", LegalForm.OOO)).isFalse();
        assertThat(RussianTaxIds.isValidCompanyInn("0000000000", LegalForm.OOO)).isFalse();
    }

    @Test
    void individualInnUsesTwelveDigitsAndTwoControlDigits() {
        assertThat(RussianTaxIds.isValidIndividualInn("500100732259")).isTrue();
        assertThat(RussianTaxIds.isValidIndividualInn("500100732258")).isFalse();
        assertThat(RussianTaxIds.isValidIndividualInn("7707083893")).isFalse();
        assertThat(RussianTaxIds.isValidIndividualInn("000000000000")).isFalse();
        assertThat(RussianTaxIds.isValidIndividualInn(null)).isTrue();
    }

    @Test
    void companyLengthDependsOnLegalFormButUnknownFormAcceptsBoth() {
        assertThat(RussianTaxIds.isValidCompanyInn("500100732259", LegalForm.IP)).isTrue();
        assertThat(RussianTaxIds.isValidCompanyInn("7707083893", LegalForm.IP)).isFalse();
        assertThat(RussianTaxIds.isValidCompanyInn("7707083893", LegalForm.OTHER)).isTrue();
        assertThat(RussianTaxIds.isValidCompanyInn("500100732259", LegalForm.OTHER)).isTrue();
        assertThat(RussianTaxIds.isValidCompanyInn("7707083893", null)).isTrue();
        assertThat(RussianTaxIds.isValidCompanyInn(null, LegalForm.OOO)).isTrue();
    }

    @Test
    void kppHasFourPlusTwoPlusThreeStructure() {
        assertThat(RussianTaxIds.isValidKpp("773601001")).isTrue();
        assertThat(RussianTaxIds.isValidKpp("770101001")).isTrue();
        assertThat(RussianTaxIds.isValidKpp("77360100")).isFalse();
        assertThat(RussianTaxIds.isValidKpp("000000000")).isFalse();
        assertThat(RussianTaxIds.isValidKpp("770001001")).isFalse();
        assertThat(RussianTaxIds.isValidKpp("7736A1001")).isFalse();
        assertThat(RussianTaxIds.isValidKpp(null)).isTrue();
    }
}
