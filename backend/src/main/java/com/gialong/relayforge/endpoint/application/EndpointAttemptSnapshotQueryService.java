package com.gialong.relayforge.endpoint.application;

import com.gialong.relayforge.endpoint.api.EndpointAttemptSnapshot;
import com.gialong.relayforge.endpoint.api.EndpointAttemptSnapshotQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Serializes endpoint mutation with the delivery attempt-start transaction.
 */
@Service
class EndpointAttemptSnapshotQueryService implements EndpointAttemptSnapshotQuery {

    private final EndpointStore endpointStore;
    private final SecretCipher secretCipher;

    EndpointAttemptSnapshotQueryService(EndpointStore endpointStore, SecretCipher secretCipher) {
        this.endpointStore = Objects.requireNonNull(endpointStore, "endpointStore must not be null");
        this.secretCipher = Objects.requireNonNull(secretCipher, "secretCipher must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<EndpointAttemptSnapshot> lockAndFindEnabledAttemptSnapshot(UUID projectId, UUID endpointId) {
        UUID requiredProjectId = Objects.requireNonNull(projectId, "projectId must not be null");
        UUID requiredEndpointId = Objects.requireNonNull(endpointId, "endpointId must not be null");
        return endpointStore.lockForAttempt(requiredProjectId, requiredEndpointId)
                .filter(LockedEndpointAttemptConfiguration::enabled)
                .map(configuration -> new EndpointAttemptSnapshot(
                        configuration.projectId(),
                        configuration.endpointId(),
                        configuration.destinationUrl(),
                        new DecryptingEndpointSigningMaterial(
                                configuration.projectId(),
                                configuration.endpointId(),
                                configuration.encryptedSigningSecret(),
                                secretCipher
                        )
                ));
    }
}
