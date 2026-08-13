package com.gialong.relayforge.endpoint.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Stable endpoint identity selected during event acceptance. URL and signing material are deliberately absent.
 */
public record RoutingEndpoint(UUID endpointId) {

    public RoutingEndpoint {
        Objects.requireNonNull(endpointId, "endpointId must not be null");
    }
}
