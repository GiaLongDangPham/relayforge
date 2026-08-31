package com.gialong.relayforge.delivery.api;

import com.gialong.relayforge.endpoint.api.EndpointAttemptSnapshot;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One committed, in-memory-only dispatch instruction. It must be closed when local handling stops.
 */
public final class DispatchInstruction implements AutoCloseable {

    private final UUID projectId;
    private final UUID eventId;
    private final UUID deliveryId;
    private final UUID attemptId;
    private final UUID claimToken;
    private final int attemptNumber;
    private final String eventType;
    private final Instant acceptedAt;
    private final Instant startedAt;
    private final Instant leaseExpiresAt;
    private final EndpointAttemptSnapshot endpointSnapshot;
    private byte[] payloadJson;
    private boolean closed;

    public DispatchInstruction(
            UUID projectId,
            UUID eventId,
            UUID deliveryId,
            UUID attemptId,
            UUID claimToken,
            int attemptNumber,
            String eventType,
            Instant acceptedAt,
            Instant startedAt,
            Instant leaseExpiresAt,
            byte[] payloadJson,
            EndpointAttemptSnapshot endpointSnapshot
    ) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.deliveryId = Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        this.claimToken = Objects.requireNonNull(claimToken, "claimToken must not be null");
        if (attemptNumber < 1 || attemptNumber > 5) {
            throw new IllegalArgumentException("attemptNumber must be between one and five");
        }
        this.attemptNumber = attemptNumber;
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt must not be null");
        this.payloadJson = Arrays.copyOf(
                Objects.requireNonNull(payloadJson, "payloadJson must not be null"),
                payloadJson.length
        );
        this.endpointSnapshot = Objects.requireNonNull(endpointSnapshot, "endpointSnapshot must not be null");
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID eventId() {
        return eventId;
    }

    public UUID deliveryId() {
        return deliveryId;
    }

    public UUID attemptId() {
        return attemptId;
    }

    /** Endpoint identity retained in the immutable attempt snapshot, safe for delivery-state transitions. */
    public UUID endpointId() {
        return endpointSnapshot.endpointId();
    }

    public UUID claimToken() {
        return claimToken;
    }

    public int attemptNumber() {
        return attemptNumber;
    }

    public String eventType() {
        return eventType;
    }

    public Instant acceptedAt() {
        return acceptedAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant leaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String destinationUrl() {
        return endpointSnapshot.destinationUrl();
    }

    /**
     * Returns a caller-owned plaintext signing-secret copy for one HMAC operation.
     */
    public byte[] signingSecret() {
        ensureOpen();
        return endpointSnapshot.signingSecret();
    }

    /**
     * Returns a caller-owned copy of the immutable event payload JSON.
     */
    public synchronized byte[] payloadJson() {
        ensureOpen();
        return Arrays.copyOf(payloadJson, payloadJson.length);
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            Arrays.fill(payloadJson, (byte) 0);
            payloadJson = new byte[0];
            endpointSnapshot.close();
            closed = true;
        }
    }

    @Override
    public String toString() {
        return "DispatchInstruction[eventId=" + eventId + ", deliveryId=" + deliveryId + ", attemptId="
                + attemptId + ", attemptNumber=" + attemptNumber + ", destination=<redacted>, payload=<redacted>]";
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("dispatch instruction is closed");
        }
    }
}
