package com.gialong.relayforge.endpoint.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Delivery-facing endpoint boundary that locks one configuration row while an attempt crosses its start boundary.
 */
public interface EndpointAttemptSnapshotQuery {

    /**
     * Joins the caller transaction, locks the endpoint row, and returns a snapshot only while it is enabled.
     */
    Optional<EndpointAttemptSnapshot> lockAndFindEnabledAttemptSnapshot(UUID projectId, UUID endpointId);
}
