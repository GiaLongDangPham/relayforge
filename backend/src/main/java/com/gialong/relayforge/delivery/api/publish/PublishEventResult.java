package com.gialong.relayforge.delivery.api.publish;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Secret-free acceptance result. A true idempotentReplay never creates new delivery work.
 */
public record PublishEventResult(
        UUID eventId,
        UUID projectId,
        String eventType,
        Instant acceptedAt,
        int deliveryCount,
        boolean idempotentReplay
) {

    public PublishEventResult {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        if (deliveryCount < 0) {
            throw new IllegalArgumentException("deliveryCount must not be negative");
        }
    }
}
