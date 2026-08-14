package com.gialong.relayforge.endpoint.api;

import java.util.Objects;
import java.util.UUID;

/**
 * In-memory endpoint configuration captured at the attempt-start boundary.
 *
 * <p>The encrypted signing material is intentionally opaque to delivery state and general API responses. A worker
 * may decrypt it only when it is ready to sign one outbound request.</p>
 */
public final class EndpointAttemptSnapshot implements AutoCloseable {

    private final UUID projectId;
    private final UUID endpointId;
    private final String destinationUrl;
    private final EndpointSigningMaterial signingMaterial;

    public EndpointAttemptSnapshot(
            UUID projectId,
            UUID endpointId,
            String destinationUrl,
            EndpointSigningMaterial signingMaterial
    ) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId must not be null");
        this.destinationUrl = Objects.requireNonNull(destinationUrl, "destinationUrl must not be null");
        this.signingMaterial = Objects.requireNonNull(signingMaterial, "signingMaterial must not be null");
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

    /**
     * Returns a caller-owned plaintext copy for one dispatch. The caller must clear it after HMAC construction.
     */
    public byte[] signingSecret() {
        return signingMaterial.decryptForDispatch();
    }

    @Override
    public void close() {
        signingMaterial.close();
    }

    @Override
    public String toString() {
        return "EndpointAttemptSnapshot[projectId=" + projectId + ", endpointId=" + endpointId
                + ", destinationUrl=<redacted>, signingMaterial=<redacted>]";
    }
}
