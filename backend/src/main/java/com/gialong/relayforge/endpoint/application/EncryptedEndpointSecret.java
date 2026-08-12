package com.gialong.relayforge.endpoint.application;

import java.util.Arrays;
import java.util.Objects;

/**
 * Internal encrypted envelope ready for persistence. It avoids a plaintext getter and redacts diagnostics.
 */
public final class EncryptedEndpointSecret {

    private final String keyReference;
    private final byte[] ciphertext;

    public EncryptedEndpointSecret(String keyReference, byte[] ciphertext) {
        this.keyReference = Objects.requireNonNull(keyReference, "keyReference must not be null");
        this.ciphertext = Arrays.copyOf(
                Objects.requireNonNull(ciphertext, "ciphertext must not be null"),
                ciphertext.length
        );
    }

    public String keyReference() {
        return keyReference;
    }

    public byte[] ciphertext() {
        return Arrays.copyOf(ciphertext, ciphertext.length);
    }

    @Override
    public String toString() {
        return "EncryptedEndpointSecret[keyReference=<redacted>, ciphertext=<redacted>]";
    }
}
