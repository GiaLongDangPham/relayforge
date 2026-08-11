package com.gialong.relayforge.project.application;

import com.gialong.relayforge.project.api.ProjectDetails;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectStore {

    ProjectDetails create(UUID projectId, UUID ownerId, String normalizedName);

    Optional<ProjectDetails> findOwned(UUID ownerId, UUID projectId);

    Optional<ProjectDetails> rename(UUID ownerId, UUID projectId, String normalizedName, long expectedVersion);

    List<ProjectDetails> listOwned(UUID ownerId, ProjectCursor cursor, int fetchLimit);
}
