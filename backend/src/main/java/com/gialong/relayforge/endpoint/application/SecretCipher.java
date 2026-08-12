package com.gialong.relayforge.endpoint.application;

import java.util.UUID;

/**
 * Encrypts endpoint secrets with authenticated context before persistence.
 */
public interface SecretCipher {

    EncryptedEndpointSecret encrypt(byte[] plaintext, UUID projectId, UUID endpointId);

    byte[] decrypt(EncryptedEndpointSecret encryptedSecret, UUID projectId, UUID endpointId);
}
