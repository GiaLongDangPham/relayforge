package com.gialong.relayforge.endpoint.api;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Delivery-facing safe endpoint state needed to derive owner-visible delivery status.
 */
public interface EndpointHistoryQuery {

    Set<UUID> findEnabledEndpointIds(UUID projectId);

    Map<UUID, EndpointHistoryMetadata> findHistoryMetadata(UUID projectId, Collection<UUID> endpointIds);
}
