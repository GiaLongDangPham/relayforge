package com.gialong.relayforge.delivery.application;

import java.util.Objects;
import java.util.UUID;

public record PendingDelivery(UUID id, UUID endpointId) {

    public PendingDelivery {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(endpointId, "endpointId must not be null");
    }
}
