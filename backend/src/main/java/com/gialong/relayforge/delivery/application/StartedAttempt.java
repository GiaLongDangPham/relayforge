package com.gialong.relayforge.delivery.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Database-owned timestamps returned only after a successful durable attempt start.
 */
public record StartedAttempt(UUID attemptId, int attemptNumber, Instant startedAt, Instant leaseExpiresAt) {

    public StartedAttempt {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        if (attemptNumber < 1 || attemptNumber > 5) {
            throw new IllegalArgumentException("attemptNumber must be between one and five");
        }
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt must not be null");
    }
}
