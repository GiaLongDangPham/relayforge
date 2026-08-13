package com.gialong.relayforge.delivery.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Versioned semantic command fingerprint. Stored JSON equality remains the idempotency correctness fallback.
 */
final class PublishCommandFingerprint {

    static final short VERSION = 1;

    private final ObjectMapper objectMapper;

    PublishCommandFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    byte[] fingerprint(String eventType, JsonNode payload) {
        byte[] canonicalPayload = serializeCanonical(payload);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Short.toString(VERSION).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
            digest.update(eventType.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(canonicalPayload);
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        } finally {
            Arrays.fill(canonicalPayload, (byte) 0);
        }
    }

    int canonicalPayloadSize(JsonNode payload) {
        byte[] canonicalPayload = serializeCanonical(payload);
        try {
            return canonicalPayload.length;
        } finally {
            Arrays.fill(canonicalPayload, (byte) 0);
        }
    }

    private byte[] serializeCanonical(JsonNode payload) {
        try {
            return objectMapper.writeValueAsBytes(canonicalize(Objects.requireNonNull(payload, "payload must not be null")));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("payload cannot be serialized as JSON", exception);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            List<Map.Entry<String, JsonNode>> properties = new ArrayList<>(node.properties());
            properties.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
            properties.forEach(property -> result.set(property.getKey(), canonicalize(property.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode(node.size());
            node.forEach(value -> result.add(canonicalize(value)));
            return result;
        }
        return node.deepCopy();
    }
}
