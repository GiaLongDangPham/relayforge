package com.gialong.relayforge.endpoint.api;

import java.util.Optional;
import java.util.UUID;

/** Delivery-facing query that joins the caller transaction and locks one endpoint policy row. */
public interface EndpointRetryPolicySnapshotQuery {

    Optional<EndpointRetryPolicySnapshot> lockAndFindRetryPolicy(UUID projectId, UUID endpointId);
}
