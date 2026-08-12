package com.gialong.relayforge.endpoint.api;

/**
 * The supplied endpoint version no longer identifies the current mutable state.
 */
public final class WebhookEndpointVersionConflictException extends RuntimeException {

    public WebhookEndpointVersionConflictException() {
        super("webhook endpoint version is stale");
    }
}
