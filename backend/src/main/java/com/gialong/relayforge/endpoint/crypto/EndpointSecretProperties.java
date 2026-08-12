package com.gialong.relayforge.endpoint.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Base64;
import java.util.Objects;

@ConfigurationProperties(prefix = "relayforge.endpoint-secret")
public final class EndpointSecretProperties {

    private final String encryptionKey;
    private final String keyReference;

    public EndpointSecretProperties(String encryptionKey, String keyReference) {
        this.encryptionKey = requireEncryptionKey(encryptionKey);
        this.keyReference = requireKeyReference(keyReference);
    }

    public byte[] encryptionKeyBytes() {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encryptionKey);
            if (decoded.length != 32) {
                throw new IllegalArgumentException("relayforge.endpoint-secret.encryption-key must decode to 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "relayforge.endpoint-secret.encryption-key must be Base64URL-encoded 32-byte key",
                    exception
            );
        }
    }

    public String keyReference() {
        return keyReference;
    }

    private static String requireEncryptionKey(String encryptionKey) {
        String value = Objects.requireNonNull(encryptionKey, "endpoint encryption key must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("relayforge.endpoint-secret.encryption-key must not be blank");
        }
        return value;
    }

    private static String requireKeyReference(String keyReference) {
        String value = Objects.requireNonNull(keyReference, "endpoint key reference must not be null");
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("relayforge.endpoint-secret.key-reference must be 1 to 128 characters");
        }
        return value;
    }
}
