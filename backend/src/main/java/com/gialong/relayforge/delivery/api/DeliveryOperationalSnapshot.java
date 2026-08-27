package com.gialong.relayforge.delivery.api;

import java.time.Instant;

/**
 * Small, aggregate-only view of queue state for operator metrics. It contains no tenant, event,
 * delivery, endpoint, destination, or payload data.
 */
public record DeliveryOperationalSnapshot(
        long readyDueCount,
        long pausedDueCount,
        long claimedCount,
        Instant oldestReadyDueAt
) {

    public DeliveryOperationalSnapshot {
        if (readyDueCount < 0 || pausedDueCount < 0 || claimedCount < 0) {
            throw new IllegalArgumentException("operational delivery counts must not be negative");
        }
        if (readyDueCount == 0 && oldestReadyDueAt != null) {
            throw new IllegalArgumentException("an empty ready backlog must not have an oldest due timestamp");
        }
    }

    public static DeliveryOperationalSnapshot empty() {
        return new DeliveryOperationalSnapshot(0, 0, 0, null);
    }
}
