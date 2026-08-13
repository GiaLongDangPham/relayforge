package com.gialong.relayforge.endpoint.application;

import com.gialong.relayforge.endpoint.api.EndpointRoutingQuery;
import com.gialong.relayforge.endpoint.api.RoutingEndpoint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
class EndpointRoutingQueryService implements EndpointRoutingQuery {

    private final EndpointStore endpointStore;

    EndpointRoutingQueryService(EndpointStore endpointStore) {
        this.endpointStore = Objects.requireNonNull(endpointStore, "endpointStore must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<RoutingEndpoint> findEnabledForExactEventType(UUID projectId, String eventType) {
        return endpointStore.findEnabledForExactEventType(
                Objects.requireNonNull(projectId, "projectId must not be null"),
                Objects.requireNonNull(eventType, "eventType must not be null")
        );
    }
}
