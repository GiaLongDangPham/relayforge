package com.gialong.relayforge.project.persistence;

import com.gialong.relayforge.project.api.ProjectDetails;
import com.gialong.relayforge.project.api.ProjectVersionConflictException;
import com.gialong.relayforge.project.application.ProjectCursor;
import com.gialong.relayforge.project.application.ProjectStore;
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
public class JpaProjectStore implements ProjectStore {

    private static final String PROJECT_DETAILS_QUERY = "select new com.gialong.relayforge.project.api.ProjectDetails("
            + "project.id, project.name, project.version, project.createdAt, project.updatedAt) "
            + "from Project project ";

    private final EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectDetails create(UUID projectId, UUID ownerId, String normalizedName) {
        ProjectEntity project = ProjectEntity.create(projectId, ownerId, normalizedName);
        entityManager.persist(project);
        entityManager.flush();
        return detailsOf(project);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<ProjectDetails> findOwned(UUID ownerId, UUID projectId) {
        return entityManager.createQuery(
                        PROJECT_DETAILS_QUERY
                                + "where project.ownerId = :ownerId and project.id = :projectId",
                        ProjectDetails.class
                )
                .setParameter("ownerId", ownerId)
                .setParameter("projectId", projectId)
                .getResultStream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProjectDetails> rename(UUID ownerId, UUID projectId, String normalizedName, long expectedVersion) {
        return entityManager.createQuery(
                        "from Project project where project.ownerId = :ownerId and project.id = :projectId",
                        ProjectEntity.class
                )
                .setParameter("ownerId", ownerId)
                .setParameter("projectId", projectId)
                .getResultStream()
                .findFirst()
                .map(project -> {
                    if (project.version() != expectedVersion) {
                        throw new ProjectVersionConflictException();
                    }

                    project.rename(normalizedName);
                    entityManager.flush();
                    return detailsOf(project);
                });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<ProjectDetails> listOwned(UUID ownerId, ProjectCursor cursor, int fetchLimit) {
        String query = PROJECT_DETAILS_QUERY + "where project.ownerId = :ownerId ";
        if (cursor != null) {
            query += "and (project.createdAt < :createdAt "
                    + "or (project.createdAt = :createdAt and project.id < :projectId)) ";
        }
        query += "order by project.createdAt desc, project.id desc";

        var typedQuery = entityManager.createQuery(query, ProjectDetails.class)
                .setParameter("ownerId", ownerId)
                .setMaxResults(fetchLimit);
        if (cursor != null) {
            typedQuery.setParameter("createdAt", cursor.createdAt());
            typedQuery.setParameter("projectId", cursor.projectId());
        }
        return typedQuery.getResultList();
    }

    private static ProjectDetails detailsOf(ProjectEntity project) {
        return new ProjectDetails(
                project.id(),
                project.name(),
                project.version(),
                project.createdAt(),
                project.updatedAt()
        );
    }
}
