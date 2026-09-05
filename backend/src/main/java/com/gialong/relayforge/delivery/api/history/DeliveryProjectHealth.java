package com.gialong.relayforge.delivery.api.history;

import java.time.Instant;
import java.util.Objects;

/**
 * One owner-visible observation of a project's delivery work. It is aggregate-only and does not
 * promise that a worker can claim an individual delivery immediately.
 */
public record DeliveryProjectHealth(
        Instant observedAt,
        long dueEnabledCount,
        Instant oldestDueEnabledAt,
        long retryScheduledCount,
        long inFlightCount,
        long pausedCount,
        long exhaustedCount
) {

    public DeliveryProjectHealth {
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        if (dueEnabledCount < 0 || retryScheduledCount < 0 || inFlightCount < 0
                || pausedCount < 0 || exhaustedCount < 0) {
            throw new IllegalArgumentException("delivery health counts must not be negative");
        }
        if (dueEnabledCount == 0 && oldestDueEnabledAt != null) {
            throw new IllegalArgumentException("an empty due-enabled backlog must not have an oldest timestamp");
        }
    }
}
