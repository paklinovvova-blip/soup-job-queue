package dev.soup.queue.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Canonical form of a payload, used to decide whether a repeated idempotency key carries the
 * "same" request: object keys are sorted recursively and numbers are compared by value,
 * so {@code {"b":1,"a":1.0}} and {@code {"a":1,"b":1}} hash the same (design D9).
 * We do not rely on {@code ORDER_MAP_ENTRIES_BY_KEYS}: it applies to {@code Map}, not to {@code ObjectNode}.
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private CanonicalJson() {
    }

    public static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                sorted.put(e.getKey(), canonicalize(e.getValue()));
            }
            ObjectNode out = NODES.objectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = NODES.arrayNode();
            node.forEach(child -> out.add(canonicalize(child)));
            return out;
        }
        if (node.isNumber()) {
            return NODES.numberNode(node.decimalValue().stripTrailingZeros());
        }
        return node;
    }

    /** SHA-256 (hex) of the canonical serialization. */
    public static String hash(JsonNode node) {
        try {
            byte[] bytes = MAPPER.writeValueAsString(canonicalize(node)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot hash payload", e);
        }
    }
}
