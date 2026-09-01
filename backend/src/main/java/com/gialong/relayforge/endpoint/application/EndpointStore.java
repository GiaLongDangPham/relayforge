package com.gialong.relayforge.endpoint.application;

import com.gialong.relayforge.endpoint.api.WebhookEndpointDetails;
import com.gialong.relayforge.endpoint.api.RoutingEndpoint;

import java.util.List;
import java.util.Optional;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.Map;

/**
 * Persistence boundary for endpoint-owned aggregate state.
 */
public interface EndpointStore {

    WebhookEndpointDetails create(
            UUID endpointId,
            UUID projectId,
            String normalizedName,
            String validatedDestinationUrl,
            List<String> normalizedEventTypes,
            boolean enabled,
            Integer minimumRetryDelaySeconds,
            EncryptedEndpointSecret encryptedSecret
    );

    Optional<WebhookEndpointDetails> findByProject(UUID projectId, UUID endpointId);

    List<WebhookEndpointDetails> listByProject(UUID projectId, EndpointCursor cursor, int fetchLimit);

    List<RoutingEndpoint> findEnabledForExactEventType(UUID projectId, String eventType);

    List<UUID> findEnabledEndpointIdsForClaim();

    Set<UUID> findEnabledEndpointIdsForHistory(UUID projectId);

    Map<UUID, com.gialong.relayforge.endpoint.api.EndpointHistoryMetadata> findHistoryMetadata(
            UUID projectId,
            Collection<UUID> endpointIds
    );

    Set<UUID> lockAndFindEnabledForClaim(Collection<UUID> endpointIds);

    Optional<LockedEndpointAttemptConfiguration> lockForAttempt(UUID projectId, UUID endpointId);

    Optional<LockedEndpointRetryPolicy> lockRetryPolicy(UUID projectId, UUID endpointId);

    Optional<WebhookEndpointDetails> replaceConfiguration(
            UUID projectId,
            UUID endpointId,
            String normalizedName,
            String validatedDestinationUrl,
            List<String> normalizedEventTypes,
            Integer minimumRetryDelaySeconds,
            long expectedVersion
    );

    Optional<WebhookEndpointDetails> setEnabled(
            UUID projectId,
            UUID endpointId,
            boolean enabled,
            long expectedVersion
    );
}
