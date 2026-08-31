package com.gialong.relayforge.delivery.api.processing;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque ownership evidence for one claimed delivery. It is not an attempt or dispatch instruction.
 */
public record ClaimedDelivery(
        UUID deliveryId,
        UUID projectId,
        UUID endpointId,
        UUID claimToken,
        Instant leaseExpiresAt
) {

    public ClaimedDelivery {
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(endpointId, "endpointId must not be null");
        Objects.requireNonNull(claimToken, "claimToken must not be null");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt must not be null");
    }
}
