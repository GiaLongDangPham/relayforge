package com.gialong.relayforge.endpoint.api;

import java.util.Objects;

/**
 * One-time creation result. The signing secret must not be persisted or logged in raw form.
 */
public final class CreatedWebhookEndpoint {

    private final WebhookEndpointDetails endpoint;
    private final String signingSecret;

    public CreatedWebhookEndpoint(WebhookEndpointDetails endpoint, String signingSecret) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.signingSecret = Objects.requireNonNull(signingSecret, "signingSecret must not be null");
    }

    public WebhookEndpointDetails endpoint() {
        return endpoint;
    }

    public String signingSecret() {
        return signingSecret;
    }

    @Override
    public String toString() {
        return "CreatedWebhookEndpoint[endpoint=" + endpoint + ", signingSecret=<redacted>]";
    }
}
