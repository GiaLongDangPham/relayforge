package com.gialong.relayforge.delivery.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe durable acknowledgement of a replay command; the actual HTTP work remains asynchronous. */
public record ReplayDeliveryResult(
        UUID sourceDeliveryId,
        UUID replayDeliveryId,
        UUID eventId,
        UUID endpointId,
        Instant createdAt,
        boolean idempotentReplay
) {

    public ReplayDeliveryResult {
        Objects.requireNonNull(sourceDeliveryId, "sourceDeliveryId must not be null");
        Objects.requireNonNull(replayDeliveryId, "replayDeliveryId must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(endpointId, "endpointId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
