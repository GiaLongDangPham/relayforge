package com.gialong.relayforge.endpoint.application;

import com.gialong.relayforge.endpoint.api.CreatedWebhookEndpoint;
import com.gialong.relayforge.endpoint.api.WebhookEndpointCatalog;
import com.gialong.relayforge.endpoint.api.WebhookEndpointDetails;
import com.gialong.relayforge.endpoint.api.WebhookEndpointPage;
import com.gialong.relayforge.endpoint.api.WebhookEndpointVersionConflictException;
import com.gialong.relayforge.project.api.ProjectCatalog;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
final class WebhookEndpointCatalogService implements WebhookEndpointCatalog {

    private final ProjectCatalog projectCatalog;
    private final EndpointStore endpointStore;
    private final EndpointUrlPolicy endpointUrlPolicy;
    private final EndpointSecretMaterial endpointSecretMaterial;
    private final SecretCipher secretCipher;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    WebhookEndpointCatalogService(
            ProjectCatalog projectCatalog,
            EndpointStore endpointStore,
            EndpointUrlPolicy endpointUrlPolicy,
            EndpointSecretMaterial endpointSecretMaterial,
            SecretCipher secretCipher,
            PlatformTransactionManager transactionManager
    ) {
        this.projectCatalog = Objects.requireNonNull(projectCatalog, "projectCatalog must not be null");
        this.endpointStore = Objects.requireNonNull(endpointStore, "endpointStore must not be null");
        this.endpointUrlPolicy = Objects.requireNonNull(endpointUrlPolicy, "endpointUrlPolicy must not be null");
        this.endpointSecretMaterial = Objects.requireNonNull(endpointSecretMaterial, "endpointSecretMaterial must not be null");
        this.secretCipher = Objects.requireNonNull(secretCipher, "secretCipher must not be null");
        this.writeTransaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public Optional<CreatedWebhookEndpoint> create(
            UUID ownerId,
            UUID projectId,
            String name,
            String destinationUrl,
            List<String> eventTypes,
            boolean enabled
    ) {
        return create(ownerId, projectId, name, destinationUrl, eventTypes, enabled, null);
    }

    @Override
    public Optional<CreatedWebhookEndpoint> create(
            UUID ownerId,
            UUID projectId,
            String name,
            String destinationUrl,
            List<String> eventTypes,
            boolean enabled,
            Integer minimumRetryDelaySeconds
    ) {
        UUID requiredOwnerId = requireOwnerId(ownerId);
        UUID requiredProjectId = requireProjectId(projectId);
        String normalizedName = EndpointNames.requireNormalized(name);
        String validatedUrl = endpointUrlPolicy.requireValid(destinationUrl);
        List<String> normalizedEventTypes = EndpointEventTypes.requireNormalized(eventTypes);
        Integer validatedMinimumRetryDelaySeconds = RetryPolicyDelays.requireNullable(minimumRetryDelaySeconds);
        UUID endpointId = UUID.randomUUID();
        EndpointSecretMaterial.GeneratedEndpointSecret generatedSecret = endpointSecretMaterial.generate(
                requiredProjectId,
                endpointId,
                secretCipher
        );

        return Objects.requireNonNull(
                writeTransaction.execute(status -> {
                    if (projectCatalog.findOwned(requiredOwnerId, requiredProjectId).isEmpty()) {
                        return Optional.empty();
                    }
                    WebhookEndpointDetails created = endpointStore.create(
                            endpointId,
                            requiredProjectId,
                            normalizedName,
                            validatedUrl,
                            normalizedEventTypes,
                            enabled,
                            validatedMinimumRetryDelaySeconds,
                            generatedSecret.encryptedSecret()
                    );
                    return Optional.of(new CreatedWebhookEndpoint(created, generatedSecret.rawSecret()));
                }),
                "endpoint create transaction returned no result"
        );
    }

    @Override
    public Optional<WebhookEndpointDetails> findOwned(UUID ownerId, UUID projectId, UUID endpointId) {
        UUID requiredOwnerId = requireOwnerId(ownerId);
        UUID requiredProjectId = requireProjectId(projectId);
        UUID requiredEndpointId = requireEndpointId(endpointId);
        return Objects.requireNonNull(
                readTransaction.execute(status -> {
                    if (projectCatalog.findOwned(requiredOwnerId, requiredProjectId).isEmpty()) {
                        return Optional.empty();
                    }
                    return endpointStore.findByProject(requiredProjectId, requiredEndpointId);
                }),
                "endpoint read transaction returned no result"
        );
    }

    @Override
    public Optional<WebhookEndpointPage> listOwned(UUID ownerId, UUID projectId, int limit, String cursor) {
        UUID requiredOwnerId = requireOwnerId(ownerId);
        UUID requiredProjectId = requireProjectId(projectId);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return Objects.requireNonNull(
                readTransaction.execute(status -> {
                    if (projectCatalog.findOwned(requiredOwnerId, requiredProjectId).isEmpty()) {
                        return Optional.empty();
                    }
                    EndpointCursor position = cursor == null
                            ? null
                            : EndpointCursor.decodeForOwnerAndProject(requiredOwnerId, requiredProjectId, cursor);
                    List<WebhookEndpointDetails> fetched = endpointStore.listByProject(
                            requiredProjectId,
                            position,
                            limit + 1
                    );
                    boolean hasMore = fetched.size() > limit;
                    List<WebhookEndpointDetails> items = hasMore ? fetched.subList(0, limit) : fetched;
                    String nextCursor = hasMore
                            ? EndpointCursor.encode(cursorFor(requiredOwnerId, requiredProjectId, items.getLast()))
                            : null;
                    return Optional.of(new WebhookEndpointPage(items, nextCursor));
                }),
                "endpoint list transaction returned no result"
        );
    }

    @Override
    public Optional<WebhookEndpointDetails> replaceConfiguration(
            UUID ownerId,
            UUID projectId,
            UUID endpointId,
            String name,
            String destinationUrl,
            List<String> eventTypes,
            long expectedVersion
    ) {
        return replaceConfiguration(
                ownerId,
                projectId,
                endpointId,
                name,
                destinationUrl,
                eventTypes,
                null,
                expectedVersion
        );
    }

    @Override
    public Optional<WebhookEndpointDetails> replaceConfiguration(
            UUID ownerId,
            UUID projectId,
            UUID endpointId,
            String name,
            String destinationUrl,
            List<String> eventTypes,
            Integer minimumRetryDelaySeconds,
            long expectedVersion
    ) {
        UUID requiredOwnerId = requireOwnerId(ownerId);
        UUID requiredProjectId = requireProjectId(projectId);
        UUID requiredEndpointId = requireEndpointId(endpointId);
        String normalizedName = EndpointNames.requireNormalized(name);
        String validatedUrl = endpointUrlPolicy.requireValid(destinationUrl);
        List<String> normalizedEventTypes = EndpointEventTypes.requireNormalized(eventTypes);
        Integer validatedMinimumRetryDelaySeconds = RetryPolicyDelays.requireNullable(minimumRetryDelaySeconds);
        requireExpectedVersion(expectedVersion);

        return inWriteTransaction(() -> {
            if (projectCatalog.findOwned(requiredOwnerId, requiredProjectId).isEmpty()) {
                return Optional.empty();
            }
            return endpointStore.replaceConfiguration(
                    requiredProjectId,
                    requiredEndpointId,
                    normalizedName,
                    validatedUrl,
                    normalizedEventTypes,
                    validatedMinimumRetryDelaySeconds,
                    expectedVersion
            );
        });
    }

    @Override
    public Optional<WebhookEndpointDetails> setEnabled(
            UUID ownerId,
            UUID projectId,
            UUID endpointId,
            boolean enabled,
            long expectedVersion
    ) {
        UUID requiredOwnerId = requireOwnerId(ownerId);
        UUID requiredProjectId = requireProjectId(projectId);
        UUID requiredEndpointId = requireEndpointId(endpointId);
        requireExpectedVersion(expectedVersion);

        return inWriteTransaction(() -> {
            if (projectCatalog.findOwned(requiredOwnerId, requiredProjectId).isEmpty()) {
                return Optional.empty();
            }
            return endpointStore.setEnabled(requiredProjectId, requiredEndpointId, enabled, expectedVersion);
        });
    }

    private Optional<WebhookEndpointDetails> inWriteTransaction(TransactionalWork work) {
        try {
            return Objects.requireNonNull(
                    writeTransaction.execute(status -> work.execute()),
                    "endpoint write transaction returned no result"
            );
        } catch (OptimisticLockException | OptimisticLockingFailureException exception) {
            throw new WebhookEndpointVersionConflictException();
        }
    }

    private static EndpointCursor cursorFor(UUID ownerId, UUID projectId, WebhookEndpointDetails endpoint) {
        return new EndpointCursor(ownerId, projectId, endpoint.createdAt(), endpoint.id());
    }

    private static UUID requireOwnerId(UUID ownerId) {
        return Objects.requireNonNull(ownerId, "ownerId must not be null");
    }

    private static UUID requireProjectId(UUID projectId) {
        return Objects.requireNonNull(projectId, "projectId must not be null");
    }

    private static UUID requireEndpointId(UUID endpointId) {
        return Objects.requireNonNull(endpointId, "endpointId must not be null");
    }

    private static void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }

    @FunctionalInterface
    private interface TransactionalWork {
        Optional<WebhookEndpointDetails> execute();
    }
}
