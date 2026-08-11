package com.gialong.relayforge.project.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner-scoped project creation and queries.
 */
public interface ProjectCatalog {

    ProjectDetails create(UUID ownerId, String name);

    Optional<ProjectDetails> findOwned(UUID ownerId, UUID projectId);

    Optional<ProjectDetails> rename(UUID ownerId, UUID projectId, String name, long expectedVersion);

    ProjectPage listOwned(UUID ownerId, int limit, String cursor);
}
