package com.gialong.relayforge.endpoint.application;

import com.gialong.relayforge.endpoint.api.EndpointRetryPolicySnapshot;
import com.gialong.relayforge.endpoint.api.EndpointRetryPolicySnapshotQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
class EndpointRetryPolicySnapshotQueryService implements EndpointRetryPolicySnapshotQuery {

    private final EndpointStore endpointStore;

    EndpointRetryPolicySnapshotQueryService(EndpointStore endpointStore) {
        this.endpointStore = Objects.requireNonNull(endpointStore, "endpointStore must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<EndpointRetryPolicySnapshot> lockAndFindRetryPolicy(UUID projectId, UUID endpointId) {
        return endpointStore.lockRetryPolicy(
                        Objects.requireNonNull(projectId, "projectId must not be null"),
                        Objects.requireNonNull(endpointId, "endpointId must not be null")
                )
                .map(policy -> new EndpointRetryPolicySnapshot(
                        policy.projectId(),
                        policy.endpointId(),
                        Optional.ofNullable(policy.minimumRetryDelaySeconds()).map(Duration::ofSeconds)
                ));
    }
}
