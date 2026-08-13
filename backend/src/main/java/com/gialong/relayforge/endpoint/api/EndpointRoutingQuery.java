package com.gialong.relayforge.endpoint.api;

import java.util.List;
import java.util.UUID;

/**
 * Delivery-facing routing snapshot query. It joins the caller's local transaction.
 */
public interface EndpointRoutingQuery {

    List<RoutingEndpoint> findEnabledForExactEventType(UUID projectId, String eventType);
}
