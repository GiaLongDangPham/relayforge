package com.gialong.relayforge.project.api;

import java.util.List;
import java.util.Objects;

/**
 * One owner-scoped page of projects. The cursor is opaque to callers.
 */
public record ProjectPage(List<ProjectDetails> items, String nextCursor) {

    public ProjectPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}
