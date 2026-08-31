package com.gialong.relayforge.runtime.worker;
import com.gialong.relayforge.delivery.api.processing.DispatchObservation;

import com.gialong.relayforge.delivery.api.processing.DispatchObservation;

import java.time.Duration;
import java.util.Objects;

/**
 * Monotonic local deadline; PostgreSQL time remains authoritative only for persisted leases and due-times.
 */
final class DispatchDeadline {

    private final long startedNanos;
    private final long deadlineNanos;

    DispatchDeadline(Duration timeout) {
        Duration requiredTimeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (requiredTimeout.isNegative() || requiredTimeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.startedNanos = System.nanoTime();
        this.deadlineNanos = Math.addExact(startedNanos, requiredTimeout.toNanos());
    }

    Duration remaining() {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new DestinationResolutionException(
                    DispatchObservation.Outcome.RETRYABLE_FAILURE,
                    DispatchObservation.FailureCode.DISPATCH_TIMEOUT
            );
        }
        return Duration.ofNanos(remainingNanos);
    }

    Duration elapsed() {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos));
    }
}
