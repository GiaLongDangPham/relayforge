package com.gialong.relayforge.project.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates and parses publisher credentials. The configured pepper is never persisted.
 */
@Component
final class PublisherApiKeyMaterial {

    private static final String TOKEN_PREFIX = "rf_live_";
    private static final int SECRET_BYTE_LENGTH = 32;
    private static final int SECRET_TEXT_LENGTH = 43;
    private static final int KEY_HINT_ID_LENGTH = 16;

    private final byte[] pepper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] dummyDigest;

    PublisherApiKeyMaterial(@Value("${relayforge.security.api-key-pepper}") String configuredPepper) {
        String requiredPepper = Objects.requireNonNull(configuredPepper, "API-key pepper must not be null");
        if (requiredPepper.isBlank()) {
            throw new IllegalArgumentException("relayforge.security.api-key-pepper must not be blank");
        }
        this.pepper = requiredPepper.getBytes(StandardCharsets.UTF_8);
        this.dummyDigest = digest("relayforge-invalid-publisher-key".getBytes(StandardCharsets.UTF_8));
    }

    GeneratedApiKey generate() {
        UUID apiKeyId = UUID.randomUUID();
        byte[] secret = new byte[SECRET_BYTE_LENGTH];
        secureRandom.nextBytes(secret);
        try {
            String encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            String rawKey = TOKEN_PREFIX + apiKeyId + "." + encodedSecret;
            return new GeneratedApiKey(apiKeyId, keyHintFor(apiKeyId), rawKey, digest(secret));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    Optional<ParsedApiKey> parse(String rawApiKey) {
        if (rawApiKey == null || !rawApiKey.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        int secretSeparator = rawApiKey.indexOf('.', TOKEN_PREFIX.length());
        if (secretSeparator != TOKEN_PREFIX.length() + 36
                || rawApiKey.indexOf('.', secretSeparator + 1) >= 0
                || rawApiKey.length() != secretSeparator + 1 + SECRET_TEXT_LENGTH) {
            return Optional.empty();
        }

        String encodedId = rawApiKey.substring(TOKEN_PREFIX.length(), secretSeparator);
        String encodedSecret = rawApiKey.substring(secretSeparator + 1);
        if (!encodedSecret.matches("[A-Za-z0-9_-]{43}")) {
            return Optional.empty();
        }
        try {
            UUID apiKeyId = UUID.fromString(encodedId);
            if (!apiKeyId.toString().equals(encodedId)) {
                return Optional.empty();
            }
            byte[] secret = Base64.getUrlDecoder().decode(encodedSecret);
            if (secret.length != SECRET_BYTE_LENGTH) {
                Arrays.fill(secret, (byte) 0);
                return Optional.empty();
            }
            return Optional.of(new ParsedApiKey(apiKeyId, secret));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    byte[] digest(byte[] secret) {
        Objects.requireNonNull(secret, "secret must not be null");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(secret);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 must be available", exception);
        }
    }

    byte[] dummyDigest() {
        return Arrays.copyOf(dummyDigest, dummyDigest.length);
    }

    private static String keyHintFor(UUID apiKeyId) {
        ByteBuffer bytes = ByteBuffer.allocate(16)
                .putLong(apiKeyId.getMostSignificantBits())
                .putLong(apiKeyId.getLeastSignificantBits());
        String encodedId = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
        return TOKEN_PREFIX + encodedId.substring(0, KEY_HINT_ID_LENGTH);
    }

    static final class GeneratedApiKey {

        private final UUID apiKeyId;
        private final String keyHint;
        private final String rawKey;
        private final byte[] secretDigest;

        private GeneratedApiKey(UUID apiKeyId, String keyHint, String rawKey, byte[] secretDigest) {
            this.apiKeyId = apiKeyId;
            this.keyHint = keyHint;
            this.rawKey = rawKey;
            this.secretDigest = secretDigest;
        }

        UUID apiKeyId() {
            return apiKeyId;
        }

        String keyHint() {
            return keyHint;
        }

        String rawKey() {
            return rawKey;
        }

        byte[] secretDigest() {
            return Arrays.copyOf(secretDigest, secretDigest.length);
        }

        void destroy() {
            Arrays.fill(secretDigest, (byte) 0);
        }

        @Override
        public String toString() {
            return "GeneratedApiKey[apiKeyId=" + apiKeyId + ", keyHint=" + keyHint + ", rawKey=<redacted>]";
        }
    }

    static final class ParsedApiKey {

        private final UUID apiKeyId;
        private final byte[] secret;

        private ParsedApiKey(UUID apiKeyId, byte[] secret) {
            this.apiKeyId = apiKeyId;
            this.secret = secret;
        }

        UUID apiKeyId() {
            return apiKeyId;
        }

        byte[] secret() {
            return secret;
        }

        void destroy() {
            Arrays.fill(secret, (byte) 0);
        }

        @Override
        public String toString() {
            return "ParsedApiKey[apiKeyId=" + apiKeyId + ", secret=<redacted>]";
        }
    }
}
