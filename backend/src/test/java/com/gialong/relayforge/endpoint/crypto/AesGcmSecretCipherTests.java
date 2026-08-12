package com.gialong.relayforge.endpoint.crypto;

import com.gialong.relayforge.endpoint.application.EncryptedEndpointSecret;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmSecretCipherTests {

    @Test
    void encryptsAndDecryptsOnlyForTheOriginalProjectAndEndpointContext() {
        AesGcmSecretCipher cipher = new AesGcmSecretCipher(new EndpointSecretProperties(
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "test-local-aes-gcm-v1"
        ));
        UUID projectId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        byte[] plaintext = "seeded-endpoint-secret-marker".getBytes(StandardCharsets.UTF_8);
        EncryptedEndpointSecret encrypted = cipher.encrypt(plaintext, projectId, endpointId);
        byte[] decrypted = cipher.decrypt(encrypted, projectId, endpointId);
        try {
            assertThat(containsSequence(encrypted.ciphertext(), plaintext)).isFalse();
            assertThat(decrypted).containsExactly(plaintext);
            assertThatThrownBy(() -> cipher.decrypt(encrypted, projectId, UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(encrypted.toString()).doesNotContain("test-local-aes-gcm-v1", "seeded-endpoint-secret-marker");
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(decrypted, (byte) 0);
        }
    }

    private static boolean containsSequence(byte[] haystack, byte[] needle) {
        for (int index = 0; index <= haystack.length - needle.length; index++) {
            boolean matches = true;
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
