package com.gialong.relayforge.endpoint.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Endpoint-owned locked configuration used only to create an attempt snapshot inside the caller transaction.
 */
public final class LockedEndpointAttemptConfiguration {

    private final UUID projectId;
    private final UUID endpointId;
    private final String destinationUrl;
    private final boolean enabled;
    private final EncryptedEndpointSecret encryptedSigningSecret;

    public LockedEndpointAttemptConfiguration(
            UUID projectId,
            UUID endpointId,
            String destinationUrl,
            boolean enabled,
            EncryptedEndpointSecret encryptedSigningSecret
    ) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId must not be null");
        this.destinationUrl = Objects.requireNonNull(destinationUrl, "destinationUrl must not be null");
        this.enabled = enabled;
        this.encryptedSigningSecret = Objects.requireNonNull(
                encryptedSigningSecret,
                "encryptedSigningSecret must not be null"
        );
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID endpointId() {
        return endpointId;
    }

    public String destinationUrl() {
        return destinationUrl;
    }

    public boolean enabled() {
        return enabled;
    }

    public EncryptedEndpointSecret encryptedSigningSecret() {
        return encryptedSigningSecret;
    }

    @Override
    public String toString() {
        return "LockedEndpointAttemptConfiguration[projectId=" + projectId + ", endpointId=" + endpointId
                + ", destinationUrl=<redacted>, signingMaterial=<redacted>]";
    }
}
