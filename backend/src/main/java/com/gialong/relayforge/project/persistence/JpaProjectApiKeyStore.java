package com.gialong.relayforge.project.persistence;

import com.gialong.relayforge.project.api.ProjectApiKeyDetails;
import com.gialong.relayforge.project.application.ProjectApiKeyCursor;
import com.gialong.relayforge.project.application.ProjectApiKeyStore;
import com.gialong.relayforge.project.application.PublisherApiKeyCandidate;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JpaProjectApiKeyStore implements ProjectApiKeyStore {

    private static final String DETAILS_QUERY = "select new com.gialong.relayforge.project.api.ProjectApiKeyDetails("
            + "apiKey.id, apiKey.displayName, apiKey.keyHint, apiKey.createdAt, apiKey.revokedAt) "
            + "from ProjectApiKey apiKey, Project project "
            + "where apiKey.projectId = project.id "
            + "and project.ownerId = :ownerId and project.id = :projectId ";

    private final EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProjectApiKeyDetails> create(
            UUID ownerId,
            UUID projectId,
            UUID apiKeyId,
            String normalizedDisplayName,
            String keyHint,
            byte[] secretDigest
    ) {
        if (!existsOwnedProject(ownerId, projectId)) {
            return Optional.empty();
        }
        ProjectApiKeyEntity apiKey = ProjectApiKeyEntity.create(
                apiKeyId,
                projectId,
                normalizedDisplayName,
                keyHint,
                secretDigest
        );
        entityManager.persist(apiKey);
        entityManager.flush();
        return Optional.of(detailsOf(apiKey));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean existsOwnedProject(UUID ownerId, UUID projectId) {
        return !entityManager.createQuery(
                        "select project.id from Project project where project.ownerId = :ownerId and project.id = :projectId",
                        UUID.class
                )
                .setParameter("ownerId", ownerId)
                .setParameter("projectId", projectId)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<ProjectApiKeyDetails> listOwned(
            UUID ownerId,
            UUID projectId,
            ProjectApiKeyCursor cursor,
            int fetchLimit
    ) {
        String query = DETAILS_QUERY;
        if (cursor != null) {
            query += "and (apiKey.createdAt < :createdAt "
                    + "or (apiKey.createdAt = :createdAt and apiKey.id < :apiKeyId)) ";
        }
        query += "order by apiKey.createdAt desc, apiKey.id desc";

        var typedQuery = entityManager.createQuery(query, ProjectApiKeyDetails.class)
                .setParameter("ownerId", ownerId)
                .setParameter("projectId", projectId)
                .setMaxResults(fetchLimit);
        if (cursor != null) {
            typedQuery.setParameter("createdAt", cursor.createdAt());
            typedQuery.setParameter("apiKeyId", cursor.apiKeyId());
        }
        return typedQuery.getResultList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProjectApiKeyDetails> revoke(UUID ownerId, UUID projectId, UUID apiKeyId) {
        Optional<ProjectApiKeyDetails> existing = detailsForOwned(ownerId, projectId, apiKeyId);
        if (existing.isEmpty() || existing.orElseThrow().revokedAt() != null) {
            return existing;
        }

        entityManager.createQuery(
                        "update ProjectApiKey apiKey set apiKey.revokedAt = CURRENT_TIMESTAMP "
                                + "where apiKey.id = :apiKeyId and apiKey.revokedAt is null"
                )
                .setParameter("apiKeyId", apiKeyId)
                .executeUpdate();
        entityManager.clear();
        return detailsForOwned(ownerId, projectId, apiKeyId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<PublisherApiKeyCandidate> findPublisherCandidate(UUID apiKeyId) {
        return entityManager.createQuery(
                        "select new com.gialong.relayforge.project.application.PublisherApiKeyCandidate("
                                + "apiKey.id, apiKey.projectId, apiKey.secretDigest, apiKey.revokedAt) "
                                + "from ProjectApiKey apiKey where apiKey.id = :apiKeyId",
                        PublisherApiKeyCandidate.class
                )
                .setParameter("apiKeyId", apiKeyId)
                .getResultStream()
                .findFirst();
    }

    private Optional<ProjectApiKeyDetails> detailsForOwned(UUID ownerId, UUID projectId, UUID apiKeyId) {
        return entityManager.createQuery(
                        DETAILS_QUERY + "and apiKey.id = :apiKeyId",
                        ProjectApiKeyDetails.class
                )
                .setParameter("ownerId", ownerId)
                .setParameter("projectId", projectId)
                .setParameter("apiKeyId", apiKeyId)
                .getResultStream()
                .findFirst();
    }

    private static ProjectApiKeyDetails detailsOf(ProjectApiKeyEntity apiKey) {
        return new ProjectApiKeyDetails(
                apiKey.id(),
                apiKey.displayName(),
                apiKey.keyHint(),
                apiKey.createdAt(),
                apiKey.revokedAt()
        );
    }
}
