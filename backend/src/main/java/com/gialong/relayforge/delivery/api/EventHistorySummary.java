package com.gialong.relayforge.delivery.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Safe list-level event metadata; event payload is intentionally absent. */
public record EventHistorySummary(UUID id, String eventType, Instant acceptedAt, int deliveryCount) {

    public EventHistorySummary {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        if (deliveryCount < 0) {
            throw new IllegalArgumentException("deliveryCount must not be negative");
        }
    }
}
