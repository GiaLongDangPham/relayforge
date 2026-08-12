package com.gialong.relayforge.endpoint.api;

import java.util.List;
import java.util.Objects;

/**
 * One owner- and project-scoped page of endpoint metadata.
 */
public record WebhookEndpointPage(List<WebhookEndpointDetails> items, String nextCursor) {

    public WebhookEndpointPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}
