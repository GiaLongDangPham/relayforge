package com.gialong.relayforge.endpoint.application;

import com.gialong.relayforge.endpoint.api.EndpointHistoryMetadata;
import com.gialong.relayforge.endpoint.api.EndpointHistoryQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Joins a delivery history transaction without exposing endpoint persistence or destination data.
 */
@Service
class EndpointHistoryQueryService implements EndpointHistoryQuery {

    private final EndpointStore endpointStore;

    EndpointHistoryQueryService(EndpointStore endpointStore) {
        this.endpointStore = Objects.requireNonNull(endpointStore, "endpointStore must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Set<UUID> findEnabledEndpointIds(UUID projectId) {
        return endpointStore.findEnabledEndpointIdsForHistory(
                Objects.requireNonNull(projectId, "projectId must not be null")
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<UUID, EndpointHistoryMetadata> findHistoryMetadata(UUID projectId, Collection<UUID> endpointIds) {
        return endpointStore.findHistoryMetadata(
                Objects.requireNonNull(projectId, "projectId must not be null"),
                Set.copyOf(Objects.requireNonNull(endpointIds, "endpointIds must not be null"))
        );
    }
}
