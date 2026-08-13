package com.gialong.relayforge.delivery.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StoredEvent(UUID id, UUID projectId, String eventType, Instant acceptedAt) {

    public StoredEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
    }
}
