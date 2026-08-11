package com.gialong.relayforge.project.application;

import com.gialong.relayforge.project.api.ProjectCatalog;
import com.gialong.relayforge.project.api.ProjectDetails;
import com.gialong.relayforge.project.api.ProjectPage;
import com.gialong.relayforge.project.api.ProjectVersionConflictException;
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
final class ProjectCatalogService implements ProjectCatalog {

    private final ProjectStore projectStore;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    ProjectCatalogService(ProjectStore projectStore, PlatformTransactionManager transactionManager) {
        this.projectStore = Objects.requireNonNull(projectStore, "projectStore must not be null");
        this.writeTransaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public ProjectDetails create(UUID ownerId, String name) {
        UUID requiredOwnerId = requireOwnerId(ownerId);
        String normalizedName = ProjectNames.requireNormalized(name);
        return Objects.requireNonNull(
                writeTransaction.execute(status -> projectStore.create(UUID.randomUUID(), requiredOwnerId, normalizedName)),
                "project create transaction returned no result"
        );
    }

    @Override
    public Optional<ProjectDetails> findOwned(UUID ownerId, UUID projectId) {
        UUID requiredOwnerId = requireOwnerId(ownerId);
        UUID requiredProjectId = requireProjectId(projectId);
        return Objects.requireNonNull(
                readTransaction.execute(status -> projectStore.findOwned(requiredOwnerId, requiredProjectId)),
                "project read transaction returned no result"
        );
    }

    @Override
    public Optional<ProjectDetails> rename(UUID ownerId, UUID projectId, String name, long expectedVersion) {
        UUID requiredOwnerId = requireOwnerId(ownerId);
        UUID requiredProjectId = requireProjectId(projectId);
        String normalizedName = ProjectNames.requireNormalized(name);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }

        try {
            return Objects.requireNonNull(
                    writeTransaction.execute(status -> projectStore.rename(
                            requiredOwnerId,
                            requiredProjectId,
                            normalizedName,
                            expectedVersion
                    )),
                    "project rename transaction returned no result"
            );
        } catch (OptimisticLockException | OptimisticLockingFailureException exception) {
            throw new ProjectVersionConflictException();
        }
    }

    @Override
    public ProjectPage listOwned(UUID ownerId, int limit, String cursor) {
        UUID requiredOwnerId = requireOwnerId(ownerId);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        ProjectCursor position = cursor == null ? null : ProjectCursor.decodeForOwner(requiredOwnerId, cursor);
        List<ProjectDetails> fetched = Objects.requireNonNull(
                readTransaction.execute(status -> projectStore.listOwned(requiredOwnerId, position, limit + 1)),
                "project list transaction returned no result"
        );
        boolean hasMore = fetched.size() > limit;
        List<ProjectDetails> items = hasMore ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasMore
                ? ProjectCursor.encode(cursorFor(requiredOwnerId, items.getLast()))
                : null;
        return new ProjectPage(items, nextCursor);
    }

    private static ProjectCursor cursorFor(UUID ownerId, ProjectDetails project) {
        return new ProjectCursor(ownerId, project.createdAt(), project.id());
    }

    private static UUID requireOwnerId(UUID ownerId) {
        return Objects.requireNonNull(ownerId, "ownerId must not be null");
    }

    private static UUID requireProjectId(UUID projectId) {
        return Objects.requireNonNull(projectId, "projectId must not be null");
    }
}
