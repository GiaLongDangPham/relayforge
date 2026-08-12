package com.gialong.relayforge.project.api;

import java.util.Objects;

/**
 * The one-time API-key creation result. Its raw key must never be persisted or logged.
 */
public final class CreatedProjectApiKey {

    private final ProjectApiKeyDetails apiKey;
    private final String rawKey;

    public CreatedProjectApiKey(ProjectApiKeyDetails apiKey, String rawKey) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey must not be null");
        this.rawKey = Objects.requireNonNull(rawKey, "rawKey must not be null");
    }

    public ProjectApiKeyDetails apiKey() {
        return apiKey;
    }

    public String rawKey() {
        return rawKey;
    }

    @Override
    public String toString() {
        return "CreatedProjectApiKey[apiKey=" + apiKey + ", rawKey=<redacted>]";
    }
}
