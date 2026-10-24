package ru.cashflow.orchestrator.features.snapshot.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Canonical fingerprint profile of handoff §5. Input is a ForecastSnapshot JSON tree whose collections are already
 * sorted by the builder; this class only removes snapshotId/fingerprint at every ForecastSnapshot level (including
 * nested calibration origin inputs), sorts object keys and hashes compact UTF-8 JSON without BOM.
 */
public final class Fingerprint {

    private static final ObjectMapper COMPACT = new ObjectMapper();

    private Fingerprint() {
    }

    public static String of(JsonNode snapshot) {
        return sha256(canonical(snapshot));
    }

    public static String canonical(JsonNode snapshot) {
        JsonNode stripped = strip(snapshot.deepCopy(), true);
        try {
            return COMPACT.writeValueAsString(sortKeys(stripped));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JsonNode strip(JsonNode node, boolean snapshotLevel) {
        if (node instanceof ObjectNode object) {
            if (snapshotLevel) {
                object.remove("snapshotId");
                object.remove("fingerprint");
            }
            JsonNode origins = object.get("calibrationOrigins");
            if (origins instanceof ArrayNode array) {
                for (JsonNode origin : array) {
                    if (origin instanceof ObjectNode o && o.get("input") != null) {
                        strip(o.get("input"), true);
                    }
                }
            }
        }
        return node;
    }

    private static JsonNode sortKeys(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                sorted.put(entry.getKey(), sortKeys(entry.getValue()));
            }
            ObjectNode result = COMPACT.createObjectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (node instanceof ArrayNode array) {
            List<JsonNode> items = new ArrayList<>();
            array.forEach(item -> items.add(sortKeys(item)));
            ArrayNode result = COMPACT.createArrayNode();
            items.forEach(result::add);
            return result;
        }
        return node;
    }
}
