package ru.cashflow.orchestrator.features.ledger.command;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Stable counterparty key per account (handoff §4). INN wins over the display name, so a renamed counterparty
 * keeps its key. Without INN and name the key is null: no relation may be invented for it.
 * Changing these rules changes keys, so it requires a new VERSION and a re-key migration.
 */
public final class CounterpartyKeys {

    public static final String VERSION = "CPK1";

    private CounterpartyKeys() {
    }

    public static String of(String inn, String name) {
        if (inn != null && !inn.isBlank()) {
            return "inn:" + inn.strip();
        }
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace('ё', 'е').replaceAll("[«»\"'`]", "").replaceAll("\\s+", " ").strip();
        return normalized.isEmpty() ? null : "name:" + normalized;
    }
}
