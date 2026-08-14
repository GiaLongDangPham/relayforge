package com.gialong.relayforge.delivery.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Delivery-owned data read under the current claim's row lock before crossing the attempt boundary.
 */
public final class AttemptStartCandidate {

    private final UUID deliveryId;
    private final UUID projectId;
    private final UUID endpointId;
    private final UUID eventId;
    private final UUID claimToken;
    private final int attemptCount;
    private final String eventType;
    private final Instant acceptedAt;
    private final byte[] payloadJson;

    public AttemptStartCandidate(
            UUID deliveryId,
            UUID projectId,
            UUID endpointId,
            UUID eventId,
            UUID claimToken,
            int attemptCount,
            String eventType,
            Instant acceptedAt,
            byte[] payloadJson
    ) {
        this.deliveryId = Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId must not be null");
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.claimToken = Objects.requireNonNull(claimToken, "claimToken must not be null");
        if (attemptCount < 0 || attemptCount >= 5) {
            throw new IllegalArgumentException("attemptCount must be between zero and four");
        }
        this.attemptCount = attemptCount;
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        this.payloadJson = Arrays.copyOf(
                Objects.requireNonNull(payloadJson, "payloadJson must not be null"),
                payloadJson.length
        );
    }

    public UUID deliveryId() {
        return deliveryId;
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID endpointId() {
        return endpointId;
    }

    public UUID eventId() {
        return eventId;
    }

    public UUID claimToken() {
        return claimToken;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public String eventType() {
        return eventType;
    }

    public Instant acceptedAt() {
        return acceptedAt;
    }

    public byte[] payloadJson() {
        return Arrays.copyOf(payloadJson, payloadJson.length);
    }

    @Override
    public String toString() {
        return "AttemptStartCandidate[deliveryId=" + deliveryId + ", projectId=" + projectId + ", endpointId="
                + endpointId + ", eventId=" + eventId + ", claimToken=<redacted>, payload=<redacted>]";
    }
}
