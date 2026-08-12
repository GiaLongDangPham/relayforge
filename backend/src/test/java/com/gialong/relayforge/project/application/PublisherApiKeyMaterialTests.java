package com.gialong.relayforge.project.application;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PublisherApiKeyMaterialTests {

    @Test
    void generatedKeyRoundTripsOnlyThroughItsSecretDigest() {
        PublisherApiKeyMaterial material = new PublisherApiKeyMaterial(
                "test-only-publisher-api-key-pepper-not-a-production-secret"
        );
        PublisherApiKeyMaterial.GeneratedApiKey generated = material.generate();
        PublisherApiKeyMaterial.ParsedApiKey parsed = material.parse(generated.rawKey()).orElseThrow();
        byte[] generatedDigest = generated.secretDigest();
        byte[] parsedDigest = material.digest(parsed.secret());
        try {
            assertThat(generated.rawKey()).matches("rf_live_[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}");
            assertThat(generated.keyHint()).startsWith("rf_live_").hasSize(24);
            assertThat(parsed.apiKeyId()).isEqualTo(generated.apiKeyId());
            assertThat(parsedDigest).containsExactly(generatedDigest);
            assertThat(generated.toString()).doesNotContain(generated.rawKey());
        } finally {
            parsed.destroy();
            generated.destroy();
            Arrays.fill(generatedDigest, (byte) 0);
            Arrays.fill(parsedDigest, (byte) 0);
        }
    }

    @Test
    void rejectsMalformedPublisherTokens() {
        PublisherApiKeyMaterial material = new PublisherApiKeyMaterial(
                "test-only-publisher-api-key-pepper-not-a-production-secret"
        );

        assertThat(material.parse(null)).isEmpty();
        assertThat(material.parse("rf_live_not-a-uuid.not-a-secret")).isEmpty();
        assertThat(material.parse("rf_test_550e8400-e29b-41d4-a716-446655440000.not-a-secret")).isEmpty();
    }
}
