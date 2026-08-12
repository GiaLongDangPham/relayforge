package com.gialong.relayforge.project.api;

import java.util.Optional;

/**
 * Verifies one publisher bearer token without exposing its secret or digest.
 */
public interface PublisherApiKeyVerifier {

    Optional<VerifiedPublisherProject> verify(String rawApiKey);
}
