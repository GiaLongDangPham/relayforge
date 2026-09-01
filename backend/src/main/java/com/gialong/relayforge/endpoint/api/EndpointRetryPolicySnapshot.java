package com.gialong.relayforge.endpoint.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Delivery-facing locked endpoint retry policy with no owner or destination data. */
public record EndpointRetryPolicySnapshot(UUID projectId, UUID endpointId, Optional<Duration> minimumRetryDelay) {

    public EndpointRetryPolicySnapshot {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(endpointId, "endpointId must not be null");
        minimumRetryDelay = Objects.requireNonNull(minimumRetryDelay, "minimumRetryDelay must not be null");
        minimumRetryDelay.ifPresent(delay -> {
            if (delay.getNano() != 0 || delay.getSeconds() < 5 || delay.getSeconds() > 300) {
                throw new IllegalArgumentException("minimumRetryDelay must be a whole 5 through 300 seconds");
            }
        });
    }
}
