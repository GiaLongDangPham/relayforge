package com.gialong.relayforge.endpoint.application;

import com.gialong.relayforge.endpoint.api.EndpointClaimEligibilityQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The final row lock serializes endpoint disablement with delivery claim commit.
 */
@Service
class EndpointClaimEligibilityQueryService implements EndpointClaimEligibilityQuery {

    private final EndpointStore endpointStore;

    EndpointClaimEligibilityQueryService(EndpointStore endpointStore) {
        this.endpointStore = Objects.requireNonNull(endpointStore, "endpointStore must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<UUID> findEnabledEndpointIdsForClaim() {
        return endpointStore.findEnabledEndpointIdsForClaim();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<UUID> lockAndFindEnabledForClaim(Collection<UUID> endpointIds) {
        return endpointStore.lockAndFindEnabledForClaim(
                List.copyOf(Objects.requireNonNull(endpointIds, "endpointIds must not be null"))
        );
    }
}
