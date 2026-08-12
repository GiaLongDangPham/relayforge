package com.gialong.relayforge.endpoint.application;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.security.SecureRandom;

/**
 * Produces the immutable v1 receiver-facing signing secret before a short persistence transaction.
 */
@Component
final class EndpointSecretMaterial {

    private static final String PREFIX = "whsec_";
    private static final int SECRET_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    GeneratedEndpointSecret generate(UUID projectId, UUID endpointId, SecretCipher secretCipher) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(endpointId, "endpointId must not be null");
        Objects.requireNonNull(secretCipher, "secretCipher must not be null");
        byte[] plaintext = new byte[SECRET_BYTES];
        secureRandom.nextBytes(plaintext);
        try {
            String rawSecret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(plaintext);
            EncryptedEndpointSecret encrypted = secretCipher.encrypt(plaintext, projectId, endpointId);
            return new GeneratedEndpointSecret(rawSecret, encrypted);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    static final class GeneratedEndpointSecret {

        private final String rawSecret;
        private final EncryptedEndpointSecret encryptedSecret;

        private GeneratedEndpointSecret(String rawSecret, EncryptedEndpointSecret encryptedSecret) {
            this.rawSecret = rawSecret;
            this.encryptedSecret = encryptedSecret;
        }

        String rawSecret() {
            return rawSecret;
        }

        EncryptedEndpointSecret encryptedSecret() {
            return encryptedSecret;
        }

        @Override
        public String toString() {
            return "GeneratedEndpointSecret[rawSecret=<redacted>, encryptedSecret=" + encryptedSecret + "]";
        }
    }
}
