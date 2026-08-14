package com.gialong.relayforge.endpoint.application;

import com.gialong.relayforge.endpoint.api.EndpointSigningMaterial;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Endpoint-private AES-GCM material behind the public opaque dispatch capability.
 */
final class DecryptingEndpointSigningMaterial implements EndpointSigningMaterial {

    private final UUID projectId;
    private final UUID endpointId;
    private final String encryptionKeyReference;
    private final SecretCipher secretCipher;
    private byte[] ciphertext;
    private boolean closed;

    DecryptingEndpointSigningMaterial(
            UUID projectId,
            UUID endpointId,
            EncryptedEndpointSecret encryptedSigningSecret,
            SecretCipher secretCipher
    ) {
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId must not be null");
        EncryptedEndpointSecret requiredSecret = Objects.requireNonNull(
                encryptedSigningSecret,
                "encryptedSigningSecret must not be null"
        );
        this.encryptionKeyReference = requiredSecret.keyReference();
        this.ciphertext = requiredSecret.ciphertext();
        this.secretCipher = Objects.requireNonNull(secretCipher, "secretCipher must not be null");
    }

    @Override
    public synchronized byte[] decryptForDispatch() {
        ensureOpen();
        byte[] ciphertextCopy = Arrays.copyOf(ciphertext, ciphertext.length);
        try {
            return secretCipher.decrypt(
                    new EncryptedEndpointSecret(encryptionKeyReference, ciphertextCopy),
                    projectId,
                    endpointId
            );
        } finally {
            Arrays.fill(ciphertextCopy, (byte) 0);
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            Arrays.fill(ciphertext, (byte) 0);
            ciphertext = new byte[0];
            closed = true;
        }
    }

    @Override
    public String toString() {
        return "DecryptingEndpointSigningMaterial[<redacted>]";
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("endpoint signing material is closed");
        }
    }
}
