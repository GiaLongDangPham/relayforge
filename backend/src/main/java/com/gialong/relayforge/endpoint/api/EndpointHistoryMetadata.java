package com.gialong.relayforge.endpoint.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Delivery-history-safe endpoint metadata. It deliberately excludes the destination URL and signing material.
 */
public record EndpointHistoryMetadata(UUID endpointId, String name, boolean enabled) {

    public EndpointHistoryMetadata {
        Objects.requireNonNull(endpointId, "endpointId must not be null");
        Objects.requireNonNull(name, "name must not be null");
    }
}
