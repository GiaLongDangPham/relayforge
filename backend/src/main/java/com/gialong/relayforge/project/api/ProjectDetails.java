package com.gialong.relayforge.project.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable project data safe to expose from the project capability.
 */
public record ProjectDetails(
        UUID id,
        String name,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public ProjectDetails {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
