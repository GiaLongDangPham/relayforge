package com.gialong.relayforge.runtime.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded worker settings for the durable endpoint circuit-breaker contract.
 * Runtime configuration supplies these values; delivery transitions own their interpretation.
 */
@ConfigurationProperties(prefix = "relayforge.worker.circuit-breaker")
public record CircuitBreakerProperties(
        @DefaultValue("3") int consecutiveFailureThreshold,
        @DefaultValue("30s") Duration openCooldown,
        @DefaultValue("1") int halfOpenProbeLimit
) {

    public CircuitBreakerProperties {
        if (consecutiveFailureThreshold <= 0) {
            throw new IllegalArgumentException(
                    "relayforge.worker.circuit-breaker.consecutive-failure-threshold must be positive"
            );
        }
        openCooldown = Objects.requireNonNull(openCooldown, "open-cooldown must not be null");
        if (openCooldown.isNegative() || openCooldown.isZero()) {
            throw new IllegalArgumentException("relayforge.worker.circuit-breaker.open-cooldown must be positive");
        }
        if (halfOpenProbeLimit != 1) {
            throw new IllegalArgumentException("relayforge.worker.circuit-breaker.half-open-probe-limit must be one");
        }
    }
}
