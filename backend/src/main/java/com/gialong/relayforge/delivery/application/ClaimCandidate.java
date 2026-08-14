package com.gialong.relayforge.delivery.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Delivery-module persistence carrier; it is deliberately not exposed through {@code delivery.api}.
 */
public record ClaimCandidate(UUID deliveryId, UUID projectId, UUID endpointId) {

    public ClaimCandidate {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(endpointId, "endpointId must not be null");
    }
}
