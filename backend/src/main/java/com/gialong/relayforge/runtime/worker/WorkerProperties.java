package com.gialong.relayforge.runtime.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded worker admission defaults. Polling starts only when a future attempt handler exists.
 */
@ConfigurationProperties(prefix = "relayforge.worker")
public record WorkerProperties(
        @DefaultValue("8") int maxInFlightClaims,
        @DefaultValue("15s") Duration initialClaimLease,
        @DefaultValue("20s") Duration attemptExecutionLease,
        @DefaultValue("500ms") Duration pollingInterval,
        @DefaultValue("100ms") Duration pollingJitter,
        @DefaultValue("5s") Duration recoveryInterval
) {

    public WorkerProperties {
        if (maxInFlightClaims <= 0) {
            throw new IllegalArgumentException("relayforge.worker.max-in-flight-claims must be positive");
        }
        initialClaimLease = positive(initialClaimLease, "initial-claim-lease");
        attemptExecutionLease = positive(attemptExecutionLease, "attempt-execution-lease");
        pollingInterval = positive(pollingInterval, "polling-interval");
        recoveryInterval = positive(recoveryInterval, "recovery-interval");
        pollingJitter = Objects.requireNonNull(pollingJitter, "polling-jitter must not be null");
        if (pollingJitter.isNegative()) {
            throw new IllegalArgumentException("relayforge.worker.polling-jitter must not be negative");
        }
        if (initialClaimLease.compareTo(Duration.ofSeconds(5)) <= 0) {
            throw new IllegalArgumentException("relayforge.worker.initial-claim-lease must exceed five seconds");
        }
        if (recoveryInterval.compareTo(initialClaimLease) >= 0) {
            throw new IllegalArgumentException("relayforge.worker.recovery-interval must be shorter than initial-claim-lease");
        }
        if (recoveryInterval.compareTo(attemptExecutionLease) >= 0) {
            throw new IllegalArgumentException("relayforge.worker.recovery-interval must be shorter than attempt-execution-lease");
        }
    }

    private static Duration positive(Duration value, String property) {
        Duration required = Objects.requireNonNull(value, property + " must not be null");
        if (required.isNegative() || required.isZero()) {
            throw new IllegalArgumentException("relayforge.worker." + property + " must be positive");
        }
        return required;
    }
}
