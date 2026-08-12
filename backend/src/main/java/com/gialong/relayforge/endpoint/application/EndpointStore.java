package com.gialong.relayforge.endpoint.application;

import com.gialong.relayforge.endpoint.api.WebhookEndpointDetails;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
            EncryptedEndpointSecret encryptedSecret
    );

    Optional<WebhookEndpointDetails> findByProject(UUID projectId, UUID endpointId);

    List<WebhookEndpointDetails> listByProject(UUID projectId, EndpointCursor cursor, int fetchLimit);

    Optional<WebhookEndpointDetails> replaceConfiguration(
            UUID projectId,
            UUID endpointId,
            String normalizedName,
            String validatedDestinationUrl,
            List<String> normalizedEventTypes,
            long expectedVersion
    );

    Optional<WebhookEndpointDetails> setEnabled(
            UUID projectId,
            UUID endpointId,
            boolean enabled,
            long expectedVersion
    );
}
