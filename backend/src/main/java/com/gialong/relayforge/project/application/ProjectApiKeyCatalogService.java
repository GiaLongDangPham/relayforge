package com.gialong.relayforge.project.application;

import com.gialong.relayforge.project.api.CreatedProjectApiKey;
import com.gialong.relayforge.project.api.ProjectApiKeyCatalog;
import com.gialong.relayforge.project.api.ProjectApiKeyDetails;
import com.gialong.relayforge.project.api.ProjectApiKeyPage;
import com.gialong.relayforge.project.api.PublisherApiKeyVerifier;
import com.gialong.relayforge.project.api.VerifiedPublisherProject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
final class ProjectApiKeyCatalogService implements ProjectApiKeyCatalog, PublisherApiKeyVerifier {

    private final ProjectApiKeyStore apiKeyStore;
    private final PublisherApiKeyMaterial apiKeyMaterial;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    ProjectApiKeyCatalogService(
            ProjectApiKeyStore apiKeyStore,
            PublisherApiKeyMaterial apiKeyMaterial,
            PlatformTransactionManager transactionManager
    ) {
        this.apiKeyStore = Objects.requireNonNull(apiKeyStore, "apiKeyStore must not be null");
        this.apiKeyMaterial = Objects.requireNonNull(apiKeyMaterial, "apiKeyMaterial must not be null");
        this.writeTransaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public Optional<CreatedProjectApiKey> create(UUID ownerId, UUID projectId, String displayName) {
        UUID requiredOwnerId = requireOwnerId(ownerId);
        UUID requiredProjectId = requireProjectId(projectId);
        String normalizedDisplayName = ProjectNames.requireNormalized(displayName);
        PublisherApiKeyMaterial.GeneratedApiKey generated = apiKeyMaterial.generate();
        try {
            Optional<ProjectApiKeyDetails> stored = Objects.requireNonNull(
                    writeTransaction.execute(status -> apiKeyStore.create(
                            requiredOwnerId,
                            requiredProjectId,
                            generated.apiKeyId(),
                            normalizedDisplayName,
                            generated.keyHint(),
                            generated.secretDigest()
                    )),
                    "API-key create transaction returned no result"
            );
            return stored.map(details -> new CreatedProjectApiKey(details, generated.rawKey()));
        } finally {
            generated.destroy();
        }
    }

    @Override
    public Optional<ProjectApiKeyPage> listOwned(UUID ownerId, UUID projectId, int limit, String cursor) {
        UUID requiredOwnerId = requireOwnerId(ownerId);
        UUID requiredProjectId = requireProjectId(projectId);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return Objects.requireNonNull(
                readTransaction.execute(status -> {
                    if (!apiKeyStore.existsOwnedProject(requiredOwnerId, requiredProjectId)) {
                        return Optional.empty();
                    }
                    ProjectApiKeyCursor position = cursor == null
                            ? null
                            : ProjectApiKeyCursor.decodeForOwnerAndProject(
                                    requiredOwnerId,
                                    requiredProjectId,
                                    cursor
                            );
                    List<ProjectApiKeyDetails> fetched = apiKeyStore.listOwned(
                            requiredOwnerId,
                            requiredProjectId,
                            position,
                            limit + 1
                    );
                    boolean hasMore = fetched.size() > limit;
                    List<ProjectApiKeyDetails> items = hasMore ? fetched.subList(0, limit) : fetched;
                    String nextCursor = hasMore
                            ? ProjectApiKeyCursor.encode(cursorFor(requiredOwnerId, requiredProjectId, items.getLast()))
                            : null;
                    return Optional.of(new ProjectApiKeyPage(items, nextCursor));
                }),
                "API-key list transaction returned no result"
        );
    }

    @Override
    public Optional<ProjectApiKeyDetails> revoke(UUID ownerId, UUID projectId, UUID apiKeyId) {
        UUID requiredOwnerId = requireOwnerId(ownerId);
        UUID requiredProjectId = requireProjectId(projectId);
        UUID requiredApiKeyId = Objects.requireNonNull(apiKeyId, "apiKeyId must not be null");
        return Objects.requireNonNull(
                writeTransaction.execute(status -> apiKeyStore.revoke(
                        requiredOwnerId,
                        requiredProjectId,
                        requiredApiKeyId
                )),
                "API-key revoke transaction returned no result"
        );
    }

    @Override
    public Optional<VerifiedPublisherProject> verify(String rawApiKey) {
        Optional<PublisherApiKeyMaterial.ParsedApiKey> parsed = apiKeyMaterial.parse(rawApiKey);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }

        PublisherApiKeyMaterial.ParsedApiKey parsedApiKey = parsed.orElseThrow();
        byte[] suppliedDigest = null;
        byte[] expectedDigest = null;
        try {
            Optional<PublisherApiKeyCandidate> candidate = Objects.requireNonNull(
                    readTransaction.execute(status -> apiKeyStore.findPublisherCandidate(parsedApiKey.apiKeyId())),
                    "API-key verification transaction returned no result"
            );
            suppliedDigest = apiKeyMaterial.digest(parsedApiKey.secret());
            expectedDigest = candidate.map(PublisherApiKeyCandidate::secretDigest)
                    .orElseGet(apiKeyMaterial::dummyDigest);
            boolean verified = MessageDigest.isEqual(expectedDigest, suppliedDigest);
            if (candidate.isEmpty() || !verified || candidate.orElseThrow().revoked()) {
                return Optional.empty();
            }
            PublisherApiKeyCandidate valid = candidate.orElseThrow();
            return Optional.of(new VerifiedPublisherProject(valid.projectId(), valid.apiKeyId()));
        } finally {
            parsedApiKey.destroy();
            clear(suppliedDigest);
            clear(expectedDigest);
        }
    }

    private static ProjectApiKeyCursor cursorFor(UUID ownerId, UUID projectId, ProjectApiKeyDetails apiKey) {
        return new ProjectApiKeyCursor(ownerId, projectId, apiKey.createdAt(), apiKey.id());
    }

    private static UUID requireOwnerId(UUID ownerId) {
        return Objects.requireNonNull(ownerId, "ownerId must not be null");
    }

    private static UUID requireProjectId(UUID projectId) {
        return Objects.requireNonNull(projectId, "projectId must not be null");
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
