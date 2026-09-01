package com.gialong.relayforge.endpoint.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Owner-safe endpoint metadata. Signing material is deliberately excluded.
 */
public record WebhookEndpointDetails(
        UUID id,
        UUID projectId,
        String name,
        String destinationUrl,
        List<String> eventTypes,
        boolean enabled,
        Integer minimumRetryDelaySeconds,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public WebhookEndpointDetails {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(destinationUrl, "destinationUrl must not be null");
        eventTypes = List.copyOf(Objects.requireNonNull(eventTypes, "eventTypes must not be null"));
        if (eventTypes.isEmpty()) {
            throw new IllegalArgumentException("eventTypes must not be empty");
        }
        if (minimumRetryDelaySeconds != null
                && (minimumRetryDelaySeconds < 5 || minimumRetryDelaySeconds > 300)) {
            throw new IllegalArgumentException("minimumRetryDelaySeconds must be between 5 and 300 when present");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
