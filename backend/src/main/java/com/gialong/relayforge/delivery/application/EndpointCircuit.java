package com.gialong.relayforge.delivery.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence-shaped circuit state. Absence in {@link EndpointCircuitStore}
 * means the same closed state as a new endpoint.
 */
public record EndpointCircuit(
        UUID endpointId,
        EndpointCircuitState state,
        int consecutiveQualifyingFailures,
        Instant openUntil,
        UUID probeDeliveryId,
        UUID probeClaimToken,
        Instant updatedAt
) {

    public EndpointCircuit {
        Objects.requireNonNull(endpointId, "endpointId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (consecutiveQualifyingFailures < 0) {
            throw new IllegalArgumentException("consecutiveQualifyingFailures must not be negative");
        }
        boolean hasProbe = probeDeliveryId != null || probeClaimToken != null;
        switch (state) {
            case CLOSED -> requireClosed(consecutiveQualifyingFailures, openUntil, hasProbe);
            case OPEN -> requireOpen(consecutiveQualifyingFailures, openUntil, hasProbe);
            case HALF_OPEN -> requireHalfOpen(consecutiveQualifyingFailures, openUntil, probeDeliveryId, probeClaimToken);
        }
    }

    private static void requireClosed(int failures, Instant openUntil, boolean hasProbe) {
        if (openUntil != null || hasProbe) {
            throw new IllegalArgumentException("CLOSED circuit must have no cooldown or probe data");
        }
    }

    private static void requireOpen(int failures, Instant openUntil, boolean hasProbe) {
        if (failures <= 0 || openUntil == null || hasProbe) {
            throw new IllegalArgumentException("OPEN circuit requires failures and cooldown but no probe data");
        }
    }

    private static void requireHalfOpen(
            int failures,
            Instant openUntil,
            UUID probeDeliveryId,
            UUID probeClaimToken
    ) {
        if (failures <= 0 || openUntil != null || probeDeliveryId == null || probeClaimToken == null) {
            throw new IllegalArgumentException("HALF_OPEN circuit requires failures and a complete probe fence");
        }
    }
}
