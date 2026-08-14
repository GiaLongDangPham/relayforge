package com.gialong.relayforge.delivery.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Versioned SHA-256 evidence for the exact destination URL snapshot, never a source for later dispatch.
 */
final class DestinationFingerprint {

    static final short VERSION = 1;
    private static final byte[] VERSIONED_PREFIX = "relayforge.destination.v1\u0000".getBytes(StandardCharsets.UTF_8);

    private DestinationFingerprint() {
    }

    static byte[] forExactDestinationUrl(String destinationUrl) {
        String requiredDestinationUrl = Objects.requireNonNull(destinationUrl, "destinationUrl must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(VERSIONED_PREFIX);
            return digest.digest(requiredDestinationUrl.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
