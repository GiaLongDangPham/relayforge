package com.gialong.relayforge.project.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Owner-safe API-key metadata. It intentionally contains neither a secret nor its digest.
 */
public record ProjectApiKeyDetails(
        UUID id,
        String displayName,
        String keyHint,
        Instant createdAt,
        Instant revokedAt
) {

    public ProjectApiKeyDetails {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(keyHint, "keyHint must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
