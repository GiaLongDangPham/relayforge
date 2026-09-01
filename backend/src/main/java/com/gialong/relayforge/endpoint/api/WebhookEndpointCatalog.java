package com.gialong.relayforge.endpoint.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner-scoped lifecycle for outbound webhook endpoint configuration.
 */
public interface WebhookEndpointCatalog {

    Optional<CreatedWebhookEndpoint> create(
            UUID ownerId,
            UUID projectId,
            String name,
            String destinationUrl,
            List<String> eventTypes,
            boolean enabled
    );

    default Optional<CreatedWebhookEndpoint> create(
            UUID ownerId,
            UUID projectId,
            String name,
            String destinationUrl,
            List<String> eventTypes,
            boolean enabled,
            Integer minimumRetryDelaySeconds
    ) {
        return create(ownerId, projectId, name, destinationUrl, eventTypes, enabled);
    }

    Optional<WebhookEndpointDetails> findOwned(UUID ownerId, UUID projectId, UUID endpointId);

    Optional<WebhookEndpointPage> listOwned(UUID ownerId, UUID projectId, int limit, String cursor);

    Optional<WebhookEndpointDetails> replaceConfiguration(
            UUID ownerId,
            UUID projectId,
            UUID endpointId,
            String name,
            String destinationUrl,
            List<String> eventTypes,
            long expectedVersion
    );

    default Optional<WebhookEndpointDetails> replaceConfiguration(
            UUID ownerId,
            UUID projectId,
            UUID endpointId,
            String name,
            String destinationUrl,
            List<String> eventTypes,
            Integer minimumRetryDelaySeconds,
            long expectedVersion
    ) {
        return replaceConfiguration(ownerId, projectId, endpointId, name, destinationUrl, eventTypes, expectedVersion);
    }

    Optional<WebhookEndpointDetails> setEnabled(
            UUID ownerId,
            UUID projectId,
            UUID endpointId,
            boolean enabled,
            long expectedVersion
    );
}
