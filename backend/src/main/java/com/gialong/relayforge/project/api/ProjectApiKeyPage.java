package com.gialong.relayforge.project.api;

import java.util.List;
import java.util.Objects;

/**
 * One owner-scoped page of safe API-key metadata.
 */
public record ProjectApiKeyPage(List<ProjectApiKeyDetails> items, String nextCursor) {

    public ProjectApiKeyPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}
