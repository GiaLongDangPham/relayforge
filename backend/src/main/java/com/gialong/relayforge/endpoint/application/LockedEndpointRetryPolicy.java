package com.gialong.relayforge.endpoint.application;

import java.util.UUID;

/** Endpoint-owned retry floor held under the endpoint configuration row lock. */
public record LockedEndpointRetryPolicy(UUID projectId, UUID endpointId, Integer minimumRetryDelaySeconds) {
}
