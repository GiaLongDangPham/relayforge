package com.gialong.relayforge.endpoint.crypto;

import com.gialong.relayforge.endpoint.application.EncryptedEndpointSecret;
import com.gialong.relayforge.endpoint.application.SecretCipher;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Local/test AES-256-GCM envelope. A future cloud key provider may implement the same module port.
 */
@Component
final class AesGcmSecretCipher implements SecretCipher {

    private static final byte FORMAT_VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MINIMUM_ENVELOPE_BYTES = 1 + NONCE_BYTES + 16;

    private final byte[] encryptionKey;
    private final String keyReference;
    private final SecureRandom secureRandom = new SecureRandom();

    AesGcmSecretCipher(EndpointSecretProperties properties) {
        this.encryptionKey = properties.encryptionKeyBytes();
        this.keyReference = properties.keyReference();
    }

    @Override
    public EncryptedEndpointSecret encrypt(byte[] plaintext, UUID projectId, UUID endpointId) {
        Objects.requireNonNull(plaintext, "plaintext must not be null");
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce, projectId, endpointId).doFinal(plaintext);
            ByteBuffer envelope = ByteBuffer.allocate(1 + NONCE_BYTES + encrypted.length);
            envelope.put(FORMAT_VERSION).put(nonce).put(encrypted);
            return new EncryptedEndpointSecret(keyReference, envelope.array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-GCM encryption failed", exception);
        } finally {
            Arrays.fill(nonce, (byte) 0);
        }
    }

    @Override
    public byte[] decrypt(EncryptedEndpointSecret encryptedSecret, UUID projectId, UUID endpointId) {
        Objects.requireNonNull(encryptedSecret, "encryptedSecret must not be null");
        if (!keyReference.equals(encryptedSecret.keyReference())) {
            throw new IllegalArgumentException("unknown endpoint encryption key reference");
        }
        byte[] envelope = encryptedSecret.ciphertext();
        if (envelope.length < MINIMUM_ENVELOPE_BYTES || envelope[0] != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported endpoint secret envelope");
        }
        byte[] nonce = Arrays.copyOfRange(envelope, 1, 1 + NONCE_BYTES);
        byte[] encrypted = Arrays.copyOfRange(envelope, 1 + NONCE_BYTES, envelope.length);
        try {
            return cipher(Cipher.DECRYPT_MODE, nonce, projectId, endpointId).doFinal(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("endpoint secret decryption failed", exception);
        } finally {
            Arrays.fill(envelope, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(encrypted, (byte) 0);
        }
    }

    private Cipher cipher(int mode, byte[] nonce, UUID projectId, UUID endpointId) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        byte[] context = context(projectId, endpointId);
        try {
            cipher.updateAAD(context);
        } finally {
            Arrays.fill(context, (byte) 0);
        }
        return cipher;
    }

    private static byte[] context(UUID projectId, UUID endpointId) {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(endpointId, "endpointId must not be null");
        ByteBuffer context = ByteBuffer.allocate(32);
        context.putLong(projectId.getMostSignificantBits()).putLong(projectId.getLeastSignificantBits());
        context.putLong(endpointId.getMostSignificantBits()).putLong(endpointId.getLeastSignificantBits());
        return context.array();
    }
}
