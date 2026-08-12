package com.gialong.relayforge.project.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Owner-scoped lifecycle for publisher API keys of one project.
 */
public interface ProjectApiKeyCatalog {

    Optional<CreatedProjectApiKey> create(UUID ownerId, UUID projectId, String displayName);

    Optional<ProjectApiKeyPage> listOwned(UUID ownerId, UUID projectId, int limit, String cursor);

    Optional<ProjectApiKeyDetails> revoke(UUID ownerId, UUID projectId, UUID apiKeyId);
}
