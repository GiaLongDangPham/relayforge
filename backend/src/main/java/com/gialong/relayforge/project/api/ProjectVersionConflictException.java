package com.gialong.relayforge.project.api;

/**
 * The supplied project version no longer identifies the current mutable state.
 */
public final class ProjectVersionConflictException extends RuntimeException {

    public ProjectVersionConflictException() {
        super("project version is stale");
    }
}
