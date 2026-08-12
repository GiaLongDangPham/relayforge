package com.gialong.relayforge.project.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Hash-free project identity established by a verified publisher API key.
 */
public record VerifiedPublisherProject(UUID projectId, UUID apiKeyId) {

    public VerifiedPublisherProject {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(apiKeyId, "apiKeyId must not be null");
    }
}
