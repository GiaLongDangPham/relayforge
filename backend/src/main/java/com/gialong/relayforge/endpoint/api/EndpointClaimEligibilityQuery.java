package com.gialong.relayforge.endpoint.api;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Delivery-facing endpoint eligibility boundary for one short claim transaction.
 */
public interface EndpointClaimEligibilityQuery {

    /**
     * Returns the enabled snapshot used to avoid scanning paused delivery backlog.
     */
    List<UUID> findEnabledEndpointIdsForClaim();

    /**
     * Rechecks and row-locks the candidate endpoints until the caller commits or rolls back.
     */
    Set<UUID> lockAndFindEnabledForClaim(Collection<UUID> endpointIds);
}
