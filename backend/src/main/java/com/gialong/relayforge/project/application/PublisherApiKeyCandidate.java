package com.gialong.relayforge.project.application;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal publisher-verification projection. Its digest never crosses the project public API.
 */
public final class PublisherApiKeyCandidate {

    private final UUID apiKeyId;
    private final UUID projectId;
    private final byte[] secretDigest;
    private final Instant revokedAt;

    public PublisherApiKeyCandidate(UUID apiKeyId, UUID projectId, byte[] secretDigest, Instant revokedAt) {
        this.apiKeyId = Objects.requireNonNull(apiKeyId, "apiKeyId must not be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.secretDigest = Arrays.copyOf(
                Objects.requireNonNull(secretDigest, "secretDigest must not be null"),
                secretDigest.length
        );
        this.revokedAt = revokedAt;
    }

    UUID apiKeyId() {
        return apiKeyId;
    }

    UUID projectId() {
        return projectId;
    }

    byte[] secretDigest() {
        return Arrays.copyOf(secretDigest, secretDigest.length);
    }

    boolean revoked() {
        return revokedAt != null;
    }

    @Override
    public String toString() {
        return "PublisherApiKeyCandidate[apiKeyId=" + apiKeyId
                + ", projectId=" + projectId
                + ", secretDigest=<redacted>, revoked=" + (revokedAt != null) + "]";
    }
}
