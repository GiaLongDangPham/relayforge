package com.gialong.relayforge.endpoint.persistence;

import com.gialong.relayforge.endpoint.api.WebhookEndpointDetails;
import com.gialong.relayforge.endpoint.api.WebhookEndpointVersionConflictException;
import com.gialong.relayforge.endpoint.api.RoutingEndpoint;
import com.gialong.relayforge.endpoint.api.EndpointHistoryMetadata;
import com.gialong.relayforge.endpoint.application.EncryptedEndpointSecret;
import com.gialong.relayforge.endpoint.application.EndpointCursor;
import com.gialong.relayforge.endpoint.application.EndpointStore;
import com.gialong.relayforge.endpoint.application.LockedEndpointAttemptConfiguration;
import com.gialong.relayforge.endpoint.application.LockedEndpointRetryPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class JpaEndpointStore implements EndpointStore {

    private final EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public WebhookEndpointDetails create(
            UUID endpointId,
            UUID projectId,
            String normalizedName,
            String validatedDestinationUrl,
            List<String> normalizedEventTypes,
            boolean enabled,
            Integer minimumRetryDelaySeconds,
            EncryptedEndpointSecret encryptedSecret
    ) {
        WebhookEndpointEntity endpoint = WebhookEndpointEntity.create(
                endpointId,
                projectId,
                normalizedName,
                validatedDestinationUrl,
                enabled,
                minimumRetryDelaySeconds,
                encryptedSecret.ciphertext(),
                encryptedSecret.keyReference()
        );
        entityManager.persist(endpoint);
        normalizedEventTypes.forEach(eventType -> entityManager.persist(
                EndpointSubscriptionEntity.create(endpointId, eventType)
        ));
        entityManager.flush();
        return detailsOf(endpoint, normalizedEventTypes);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<WebhookEndpointDetails> findByProject(UUID projectId, UUID endpointId) {
        return endpointByProject(projectId, endpointId).map(endpoint -> detailsOf(endpoint, eventTypesFor(List.of(endpoint.id())).get(endpoint.id())));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<WebhookEndpointDetails> listByProject(UUID projectId, EndpointCursor cursor, int fetchLimit) {
        String query = "from WebhookEndpoint endpoint where endpoint.projectId = :projectId ";
        if (cursor != null) {
            query += "and (endpoint.createdAt < :createdAt "
                    + "or (endpoint.createdAt = :createdAt and endpoint.id < :endpointId)) ";
        }
        query += "order by endpoint.createdAt desc, endpoint.id desc";
        var typedQuery = entityManager.createQuery(query, WebhookEndpointEntity.class)
                .setParameter("projectId", projectId)
                .setMaxResults(fetchLimit);
        if (cursor != null) {
            typedQuery.setParameter("createdAt", cursor.createdAt());
            typedQuery.setParameter("endpointId", cursor.endpointId());
        }
        List<WebhookEndpointEntity> endpoints = typedQuery.getResultList();
        Map<UUID, List<String>> eventTypesByEndpoint = eventTypesFor(endpoints.stream().map(WebhookEndpointEntity::id).toList());
        return endpoints.stream()
                .map(endpoint -> detailsOf(endpoint, eventTypesByEndpoint.get(endpoint.id())))
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<RoutingEndpoint> findEnabledForExactEventType(UUID projectId, String eventType) {
        return entityManager.createQuery(
                        "select new com.gialong.relayforge.endpoint.api.RoutingEndpoint(endpoint.id) "
                                + "from WebhookEndpoint endpoint, EndpointSubscription subscription "
                                + "where endpoint.id = subscription.endpointId "
                                + "and endpoint.projectId = :projectId "
                                + "and endpoint.enabled = true "
                                + "and subscription.eventType = :eventType "
                                + "order by endpoint.id",
                        RoutingEndpoint.class
                )
                .setParameter("projectId", projectId)
                .setParameter("eventType", eventType)
                .getResultList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<UUID> findEnabledEndpointIdsForClaim() {
        return entityManager.createQuery(
                        "select endpoint.id from WebhookEndpoint endpoint where endpoint.enabled = true order by endpoint.id",
                        UUID.class
                )
                .getResultList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Set<UUID> findEnabledEndpointIdsForHistory(UUID projectId) {
        return Set.copyOf(entityManager.createQuery(
                        "select endpoint.id from WebhookEndpoint endpoint "
                                + "where endpoint.projectId = :projectId and endpoint.enabled = true",
                        UUID.class
                )
                .setParameter("projectId", projectId)
                .getResultList());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<UUID, EndpointHistoryMetadata> findHistoryMetadata(UUID projectId, Collection<UUID> endpointIds) {
        if (endpointIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, EndpointHistoryMetadata> metadata = new LinkedHashMap<>();
        entityManager.createQuery(
                        "select new com.gialong.relayforge.endpoint.api.EndpointHistoryMetadata("
                                + "endpoint.id, endpoint.name, endpoint.enabled) "
                                + "from WebhookEndpoint endpoint where endpoint.projectId = :projectId "
                                + "and endpoint.id in :endpointIds",
                        EndpointHistoryMetadata.class
                )
                .setParameter("projectId", projectId)
                .setParameter("endpointIds", endpointIds)
                .getResultList()
                .forEach(endpoint -> metadata.put(endpoint.endpointId(), endpoint));
        return Map.copyOf(metadata);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<UUID> lockAndFindEnabledForClaim(Collection<UUID> endpointIds) {
        if (endpointIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> ids = List.copyOf(endpointIds);
        String placeholders = java.util.stream.IntStream.range(0, ids.size())
                .mapToObj(index -> ":endpoint" + index)
                .collect(Collectors.joining(", "));
        var query = entityManager.createNativeQuery(
                "select id from public.webhook_endpoints where enabled = true and id in (" + placeholders + ") for update"
        );
        for (int index = 0; index < ids.size(); index++) {
            query.setParameter("endpoint" + index, ids.get(index));
        }
        Set<UUID> enabledIds = new java.util.LinkedHashSet<>();
        query.getResultList().forEach(value -> enabledIds.add(UUID.class.cast(value)));
        return Set.copyOf(enabledIds);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LockedEndpointAttemptConfiguration> lockForAttempt(UUID projectId, UUID endpointId) {
        return entityManager.createQuery(
                        "from WebhookEndpoint endpoint where endpoint.projectId = :projectId and endpoint.id = :endpointId",
                        WebhookEndpointEntity.class
                )
                .setParameter("projectId", projectId)
                .setParameter("endpointId", endpointId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .map(endpoint -> new LockedEndpointAttemptConfiguration(
                        endpoint.projectId(),
                        endpoint.id(),
                        endpoint.destinationUrl(),
                        endpoint.enabled(),
                        new EncryptedEndpointSecret(
                                endpoint.encryptionKeyReference(),
                                endpoint.signingSecretCiphertext()
                        )
                ));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LockedEndpointRetryPolicy> lockRetryPolicy(UUID projectId, UUID endpointId) {
        return entityManager.createQuery(
                        "from WebhookEndpoint endpoint where endpoint.projectId = :projectId and endpoint.id = :endpointId",
                        WebhookEndpointEntity.class
                )
                .setParameter("projectId", projectId)
                .setParameter("endpointId", endpointId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .map(endpoint -> new LockedEndpointRetryPolicy(
                        endpoint.projectId(), endpoint.id(), endpoint.minimumRetryDelaySeconds()
                ));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<WebhookEndpointDetails> replaceConfiguration(
            UUID projectId,
            UUID endpointId,
            String normalizedName,
            String validatedDestinationUrl,
            List<String> normalizedEventTypes,
            Integer minimumRetryDelaySeconds,
            long expectedVersion
    ) {
        // Subscription rows are not a JPA association, so fence the complete aggregate replacement on its versioned root.
        int changed = entityManager.createNativeQuery(
                        "update public.webhook_endpoints set name = :name, destination_url = :destinationUrl, "
                                + "minimum_retry_delay_seconds = :minimumRetryDelaySeconds, "
                                + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
                                + "where id = :endpointId and project_id = :projectId and version = :expectedVersion"
                )
                .setParameter("name", normalizedName)
                .setParameter("destinationUrl", validatedDestinationUrl)
                .setParameter("minimumRetryDelaySeconds", minimumRetryDelaySeconds)
                .setParameter("endpointId", endpointId)
                .setParameter("projectId", projectId)
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        entityManager.clear();
        if (changed == 0) {
            if (endpointByProject(projectId, endpointId).isEmpty()) {
                return Optional.empty();
            }
            throw new WebhookEndpointVersionConflictException();
        }

        entityManager.createQuery("delete from EndpointSubscription subscription where subscription.endpointId = :endpointId")
                .setParameter("endpointId", endpointId)
                .executeUpdate();
        normalizedEventTypes.forEach(eventType -> entityManager.persist(
                EndpointSubscriptionEntity.create(endpointId, eventType)
        ));
        entityManager.flush();
        entityManager.clear();
        return findByProject(projectId, endpointId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<WebhookEndpointDetails> setEnabled(
            UUID projectId,
            UUID endpointId,
            boolean enabled,
            long expectedVersion
    ) {
        Optional<WebhookEndpointDetails> current = findByProject(projectId, endpointId);
        if (current.isEmpty() || current.orElseThrow().enabled() == enabled) {
            return current;
        }

        int changed = entityManager.createNativeQuery(
                        "update public.webhook_endpoints set enabled = :enabled, version = version + 1, "
                                + "updated_at = CURRENT_TIMESTAMP "
                                + "where id = :endpointId and project_id = :projectId and version = :expectedVersion "
                                + "and enabled <> :enabled"
                )
                .setParameter("enabled", enabled)
                .setParameter("endpointId", endpointId)
                .setParameter("projectId", projectId)
                .setParameter("expectedVersion", expectedVersion)
                .executeUpdate();
        entityManager.clear();
        Optional<WebhookEndpointDetails> after = findByProject(projectId, endpointId);
        if (changed == 0 && after.isPresent() && after.orElseThrow().enabled() != enabled) {
            throw new WebhookEndpointVersionConflictException();
        }
        return after;
    }

    private Optional<WebhookEndpointEntity> endpointByProject(UUID projectId, UUID endpointId) {
        return entityManager.createQuery(
                        "from WebhookEndpoint endpoint where endpoint.projectId = :projectId and endpoint.id = :endpointId",
                        WebhookEndpointEntity.class
                )
                .setParameter("projectId", projectId)
                .setParameter("endpointId", endpointId)
                .getResultStream()
                .findFirst();
    }

    private Map<UUID, List<String>> eventTypesFor(Collection<UUID> endpointIds) {
        if (endpointIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<String>> eventTypes = new LinkedHashMap<>();
        endpointIds.forEach(endpointId -> eventTypes.put(endpointId, new java.util.ArrayList<>()));
        entityManager.createQuery(
                        "from EndpointSubscription subscription where subscription.endpointId in :endpointIds "
                                + "order by subscription.endpointId, subscription.eventType",
                        EndpointSubscriptionEntity.class
                )
                .setParameter("endpointIds", endpointIds)
                .getResultList()
                .forEach(subscription -> eventTypes.get(subscription.endpointId()).add(subscription.eventType()));
        return eventTypes;
    }

    private static WebhookEndpointDetails detailsOf(WebhookEndpointEntity endpoint, List<String> eventTypes) {
        return new WebhookEndpointDetails(
                endpoint.id(),
                endpoint.projectId(),
                endpoint.name(),
                endpoint.destinationUrl(),
                eventTypes,
                endpoint.enabled(),
                endpoint.minimumRetryDelaySeconds(),
                endpoint.version(),
                endpoint.createdAt(),
                endpoint.updatedAt()
        );
    }
}
