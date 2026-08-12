package com.gialong.relayforge.project.application;

import com.gialong.relayforge.project.api.ProjectApiKeyDetails;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for project-owned API-key lifecycle state.
 */
public interface ProjectApiKeyStore {

    Optional<ProjectApiKeyDetails> create(
            UUID ownerId,
            UUID projectId,
            UUID apiKeyId,
            String normalizedDisplayName,
            String keyHint,
            byte[] secretDigest
    );

    boolean existsOwnedProject(UUID ownerId, UUID projectId);

    List<ProjectApiKeyDetails> listOwned(UUID ownerId, UUID projectId, ProjectApiKeyCursor cursor, int fetchLimit);

    Optional<ProjectApiKeyDetails> revoke(UUID ownerId, UUID projectId, UUID apiKeyId);

    Optional<PublisherApiKeyCandidate> findPublisherCandidate(UUID apiKeyId);
}
