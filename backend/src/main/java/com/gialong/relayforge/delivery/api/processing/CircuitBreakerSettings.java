package com.gialong.relayforge.delivery.api.processing;

import java.time.Duration;
import java.util.Objects;

/** Immutable delivery-owned limits used by circuit state transitions. */
public record CircuitBreakerSettings(int consecutiveFailureThreshold, Duration openCooldown) {

    public CircuitBreakerSettings {
        if (consecutiveFailureThreshold <= 0) {
            throw new IllegalArgumentException("consecutiveFailureThreshold must be positive");
        }
        openCooldown = Objects.requireNonNull(openCooldown, "openCooldown must not be null");
        if (openCooldown.isNegative() || openCooldown.isZero() || openCooldown.toMillis() == 0) {
            throw new IllegalArgumentException("openCooldown must be at least one millisecond");
        }
    }
}
