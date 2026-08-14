package com.gialong.relayforge.delivery.api;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One short-lived outbound message whose body bytes are already covered by the HMAC signature.
 *
 * <p>Only a worker outbound adapter may turn this into an HTTP request. Callers must close it after local dispatch
 * handling stops.</p>
 */
public final class SignedOutboundWebhookMessage implements AutoCloseable {

    private final UUID eventId;
    private final UUID deliveryId;
    private final UUID attemptId;
    private final int attemptNumber;
    private final long timestampSeconds;
    private byte[] body;
    private byte[] signature;
    private boolean closed;

    public SignedOutboundWebhookMessage(
            UUID eventId,
            UUID deliveryId,
            UUID attemptId,
            int attemptNumber,
            long timestampSeconds,
            byte[] body,
            byte[] signature
    ) {
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.deliveryId = Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        if (attemptNumber < 1 || attemptNumber > 5) {
            throw new IllegalArgumentException("attemptNumber must be between one and five");
        }
        if (timestampSeconds < 0) {
            throw new IllegalArgumentException("timestampSeconds must not be negative");
        }
        this.attemptNumber = attemptNumber;
        this.timestampSeconds = timestampSeconds;
        this.body = Arrays.copyOf(Objects.requireNonNull(body, "body must not be null"), body.length);
        this.signature = Arrays.copyOf(Objects.requireNonNull(signature, "signature must not be null"), signature.length);
    }

    public byte[] body() {
        ensureOpen();
        return Arrays.copyOf(body, body.length);
    }

    public Map<String, String> headers() {
        ensureOpen();
        return Map.of(
                "Content-Type", "application/json",
                "User-Agent", "RelayForge/1",
                "X-RelayForge-Event-Id", eventId.toString(),
                "X-RelayForge-Delivery-Id", deliveryId.toString(),
                "X-RelayForge-Attempt-Id", attemptId.toString(),
                "X-RelayForge-Attempt-Number", Integer.toString(attemptNumber),
                "X-RelayForge-Timestamp", Long.toString(timestampSeconds),
                "X-RelayForge-Signature", "v1=" + Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        );
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            Arrays.fill(body, (byte) 0);
            Arrays.fill(signature, (byte) 0);
            body = new byte[0];
            signature = new byte[0];
            closed = true;
        }
    }

    @Override
    public String toString() {
        return "SignedOutboundWebhookMessage[eventId=" + eventId + ", deliveryId=" + deliveryId
                + ", attemptId=" + attemptId + ", attemptNumber=" + attemptNumber
                + ", body=<redacted>, signature=<redacted>]";
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("signed outbound webhook message is closed");
        }
    }
}
