package com.gialong.relayforge.delivery.api.history;

import java.util.Objects;
import java.util.UUID;

/** Current endpoint identity safe to display beside a delivery; URL and signing material stay private. */
public record DeliveryEndpointMetadata(UUID endpointId, String name, boolean enabled) {

    public DeliveryEndpointMetadata {
        Objects.requireNonNull(endpointId, "endpointId must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }
}
