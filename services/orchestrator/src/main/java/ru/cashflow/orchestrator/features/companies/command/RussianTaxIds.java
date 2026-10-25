package ru.cashflow.orchestrator.features.companies.command;

import java.util.regex.Pattern;

import ru.cashflow.orchestrator.features.companies.model.LegalForm;

/**
 * Russian INN and KPP validation. INN is checked by its official checksum; KPP has no checksum, so its
 * structure is checked instead: four-digit tax authority, two-digit reason code, three-digit serial.
 * Employee and salary-recipient INNs must be individual (12 digits), never a legal-entity INN.
 */
public final class RussianTaxIds {

    private static final int[] LEGAL_WEIGHTS = {2, 4, 10, 3, 5, 9, 4, 6, 8};
    private static final int[] INDIVIDUAL_11_WEIGHTS = {7, 2, 4, 10, 3, 5, 9, 4, 6, 8};
    private static final int[] INDIVIDUAL_12_WEIGHTS = {3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8};
    private static final Pattern DIGITS_10 = Pattern.compile("^[0-9]{10}$");
    private static final Pattern DIGITS_12 = Pattern.compile("^[0-9]{12}$");
    private static final Pattern KPP = Pattern.compile("^[0-9]{9}$");

    private RussianTaxIds() {
    }

    /** OOO uses a 10-digit legal-entity INN, IP uses a 12-digit individual INN; unknown form accepts either. */
    public static boolean isValidCompanyInn(String value, LegalForm legalForm) {
        String inn = normalize(value);
        if (inn == null) {
            return true;
        }
        if (legalForm == LegalForm.OOO) {
            return DIGITS_10.matcher(inn).matches() && nonZero(inn) && validLegal(inn);
        }
        if (legalForm == LegalForm.IP) {
            return DIGITS_12.matcher(inn).matches() && nonZero(inn) && validIndividual(inn);
        }
        return (DIGITS_10.matcher(inn).matches() && nonZero(inn) && validLegal(inn))
                || (DIGITS_12.matcher(inn).matches() && nonZero(inn) && validIndividual(inn));
    }

    public static boolean isValidIndividualInn(String value) {
        String inn = normalize(value);
        return inn == null || (DIGITS_12.matcher(inn).matches() && nonZero(inn) && validIndividual(inn));
    }

    /** KPP is valid if it has the 4+2+3 structure and neither the region/authority nor reason code is 00. */
    public static boolean isValidKpp(String value) {
        String kpp = normalize(value);
        if (kpp == null) {
            return true;
        }
        return KPP.matcher(kpp).matches()
                && !kpp.startsWith("00")
                && !kpp.substring(2, 4).equals("00")
                && !kpp.substring(4, 6).equals("00")
                && !kpp.equals("000000000");
    }

    private static boolean nonZero(String value) {
        return value.chars().anyMatch(character -> character != '0');
    }

    private static boolean validLegal(String inn) {
        return control(inn, LEGAL_WEIGHTS, 9) == digit(inn, 9);
    }

    private static boolean validIndividual(String inn) {
        return control(inn, INDIVIDUAL_11_WEIGHTS, 10) == digit(inn, 10)
                && control(inn, INDIVIDUAL_12_WEIGHTS, 11) == digit(inn, 11);
    }

    private static int control(String inn, int[] weights, int length) {
        int sum = 0;
        for (int index = 0; index < length; index++) {
            sum += digit(inn, index) * weights[index];
        }
        return sum % 11 % 10;
    }

    private static int digit(String value, int index) {
        return value.charAt(index) - '0';
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
