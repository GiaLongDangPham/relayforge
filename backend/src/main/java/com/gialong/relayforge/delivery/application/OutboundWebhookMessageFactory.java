package com.gialong.relayforge.delivery.application;
import com.gialong.relayforge.delivery.api.processing.DispatchInstruction;
import com.gialong.relayforge.delivery.api.processing.OutboundWebhookMessageSigner;
import com.gialong.relayforge.delivery.api.processing.SignedOutboundWebhookMessage;

import com.gialong.relayforge.delivery.api.processing.DispatchInstruction;
import com.gialong.relayforge.delivery.api.processing.OutboundWebhookMessageSigner;
import com.gialong.relayforge.delivery.api.processing.SignedOutboundWebhookMessage;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Builds the exact UTF-8 webhook body and its v1 HMAC before any network I/O begins.
 */
@Service
final class OutboundWebhookMessageFactory implements OutboundWebhookMessageSigner {

    private static final String SIGNATURE_VERSION = "v1";
    private static final int ENDPOINT_SECRET_BYTES = 32;

    private final ObjectMapper objectMapper;

    OutboundWebhookMessageFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public SignedOutboundWebhookMessage sign(DispatchInstruction instruction, Instant timestamp) {
        DispatchInstruction requiredInstruction = Objects.requireNonNull(instruction, "instruction must not be null");
        long timestampSeconds = Objects.requireNonNull(timestamp, "timestamp must not be null").getEpochSecond();
        if (timestampSeconds < 0) {
            throw new IllegalArgumentException("timestamp must not be before the Unix epoch");
        }

        byte[] body = serializeBody(requiredInstruction);
        byte[] secret = null;
        byte[] bodyDigest = null;
        byte[] canonical = null;
        byte[] signature = null;
        try {
            secret = requiredInstruction.signingSecret();
            if (secret.length != ENDPOINT_SECRET_BYTES) {
                throw new IllegalStateException("endpoint signing secret has an invalid length");
            }
            bodyDigest = sha256(body);
            canonical = canonicalBytes(requiredInstruction, timestampSeconds, bodyDigest);
            signature = hmacSha256(secret, canonical);
            return new SignedOutboundWebhookMessage(
                    requiredInstruction.eventId(),
                    requiredInstruction.deliveryId(),
                    requiredInstruction.attemptId(),
                    requiredInstruction.attemptNumber(),
                    timestampSeconds,
                    body,
                    signature
            );
        } finally {
            Arrays.fill(body, (byte) 0);
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
            }
            if (bodyDigest != null) {
                Arrays.fill(bodyDigest, (byte) 0);
            }
            if (canonical != null) {
                Arrays.fill(canonical, (byte) 0);
            }
            if (signature != null) {
                Arrays.fill(signature, (byte) 0);
            }
        }
    }

    private byte[] serializeBody(DispatchInstruction instruction) {
        byte[] payload = instruction.payloadJson();
        try {
            JsonNode data = objectMapper.readTree(payload);
            if (data == null) {
                throw new IllegalStateException("committed event payload must be a JSON value");
            }
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("eventId", instruction.eventId().toString());
            body.put("eventType", instruction.eventType());
            body.put("acceptedAt", instruction.acceptedAt().toString());
            body.set("data", data);
            return objectMapper.writeValueAsBytes(body);
        } catch (JacksonException exception) {
            throw new IllegalStateException("committed event payload cannot be serialized for dispatch", exception);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    private static byte[] canonicalBytes(DispatchInstruction instruction, long timestampSeconds, byte[] bodyDigest) {
        String canonical = SIGNATURE_VERSION + '\n'
                + timestampSeconds + '\n'
                + instruction.eventId() + '\n'
                + instruction.deliveryId() + '\n'
                + instruction.attemptId() + '\n'
                + HexFormat.of().formatHex(bodyDigest);
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static byte[] hmacSha256(byte[] secret, byte[] canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(canonical);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 must be available", exception);
        }
    }
}
