package ru.cashflow.forecast.api.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class SnapshotFingerprint {
    private SnapshotFingerprint() {}

    public static String compute(JsonNode snapshot) {
        try {
            byte[] bytes = canonical(snapshot, "").toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static JsonNode canonical(JsonNode value, String field) {
        if (value.isObject()) {
            boolean snapshot =
                    value.has("dailyHistory") && value.has("asOfDate") && value.has("settings");
            var fields = new TreeMap<String, JsonNode>();
            value.properties()
                    .forEach(
                            entry -> {
                                if (!snapshot
                                        || (!entry.getKey().equals("snapshotId")
                                                && !entry.getKey().equals("fingerprint"))) {
                                    fields.put(
                                            entry.getKey(),
                                            canonical(entry.getValue(), entry.getKey()));
                                }
                            });
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            fields.forEach(object::set);
            return object;
        }
        if (value.isArray()) {
            var elements = new ArrayList<JsonNode>();
            value.forEach(element -> elements.add(canonical(element, "")));
            Comparator<JsonNode> comparator =
                    switch (field) {
                        case "dailyHistory", "actualDays" ->
                                Comparator.comparing(node -> node.path("date").asText());
                        case "payments", "actualPayments" ->
                                Comparator.comparing((JsonNode node) -> node.path("date").asText())
                                        .thenComparing(node -> node.path("paymentId").asText());
                        case "knownPlans" ->
                                Comparator.comparing(
                                                (JsonNode node) -> node.path("dueDate").asText())
                                        .thenComparing(node -> node.path("id").asText());
                        case "recurringPatterns" ->
                                Comparator.comparing(node -> node.path("id").asText());
                        case "completeRanges" ->
                                Comparator.comparing(
                                                (JsonNode node) -> node.path("fromDate").asText())
                                        .thenComparing(node -> node.path("toDate").asText())
                                        .thenComparing(node -> node.path("id").asText());
                        case "calibrationOrigins" ->
                                Comparator.comparing(
                                        node -> node.path("input").path("asOfDate").asText());
                        default -> null;
                    };
            if (comparator != null) {
                elements.sort(comparator);
            }
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            elements.forEach(array::add);
            return array;
        }
        if (value.isTextual()) {
            String text = value.asText();
            if (Set.of("id", "companyId", "accountId", "paymentId", "recurringPatternId")
                    .contains(field)) {
                return JsonNodeFactory.instance.textNode(
                        UUID.fromString(text).toString().toLowerCase(Locale.ROOT));
            }
            if (Set.of("createdAt", "updatedAt", "knownAt", "knowledgeCutoff").contains(field)) {
                return JsonNodeFactory.instance.textNode(Instant.parse(text).toString());
            }
            if (Set.of(
                            "inflow",
                            "outflow",
                            "balanceEod",
                            "amount",
                            "startBalance",
                            "bufferAmount",
                            "settledAmount",
                            "remainingAmount")
                    .contains(field)) {
                return JsonNodeFactory.instance.textNode(
                        new BigDecimal(text).setScale(2, RoundingMode.UNNECESSARY).toPlainString());
            }
        }
        return value;
    }
}
