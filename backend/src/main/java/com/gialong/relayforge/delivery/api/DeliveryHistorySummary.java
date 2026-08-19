package com.gialong.relayforge.delivery.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Secret-free summary of one original or replay delivery. */
public record DeliveryHistorySummary(
        UUID id,
        UUID eventId,
        UUID endpointId,
        UUID replayOfDeliveryId,
        DeliveryStoredState state,
        DeliveryDisplayStatus displayStatus,
        int attemptCount,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant terminalAt
) {

    public DeliveryHistorySummary {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(endpointId, "endpointId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(displayStatus, "displayStatus must not be null");
        if (attemptCount < 0 || attemptCount > 5) {
            throw new IllegalArgumentException("attemptCount must be between zero and five");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
