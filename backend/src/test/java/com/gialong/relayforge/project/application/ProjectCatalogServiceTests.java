package com.gialong.relayforge.project.application;

import com.gialong.relayforge.project.api.ProjectDetails;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectCatalogServiceTests {

    @Test
    void normalizesAndCreatesInsideOneWriteTransaction() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        UUID ownerId = UUID.randomUUID();
        AtomicReference<UUID> generatedProjectId = new AtomicReference<>();
        ProjectDetails created = projectDetails(UUID.randomUUID(), "Payments");
        ProjectStore projectStore = new ProjectStore() {
            @Override
            public ProjectDetails create(UUID projectId, UUID suppliedOwnerId, String normalizedName) {
                assertThat(transactionManager.active()).isTrue();
                assertThat(transactionManager.readOnly()).isFalse();
                assertThat(suppliedOwnerId).isEqualTo(ownerId);
                assertThat(normalizedName).isEqualTo("Payments");
                generatedProjectId.set(projectId);
                return created;
            }

            @Override
            public Optional<ProjectDetails> findOwned(UUID ignoredOwnerId, UUID ignoredProjectId) {
                throw new AssertionError("findOwned must not be called");
            }

            @Override
            public Optional<ProjectDetails> rename(
                    UUID ignoredOwnerId,
                    UUID ignoredProjectId,
                    String ignoredName,
                    long ignoredVersion
            ) {
                throw new AssertionError("rename must not be called");
            }

            @Override
            public List<ProjectDetails> listOwned(UUID ignoredOwnerId, ProjectCursor ignoredCursor, int ignoredFetchLimit) {
                throw new AssertionError("listOwned must not be called");
            }
        };
        ProjectCatalogService service = new ProjectCatalogService(projectStore, transactionManager);

        assertThat(service.create(ownerId, " Payments ")).isEqualTo(created);
        assertThat(generatedProjectId.get()).isNotNull();
        assertThat(transactionManager.transactionCount()).isEqualTo(1);
    }

    @Test
    void ownerScopedReadsUseShortReadOnlyTransactions() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        UUID ownerId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        ProjectDetails ownedProject = projectDetails(projectId, "Payments");
        ProjectStore projectStore = new ProjectStore() {
            @Override
            public ProjectDetails create(UUID ignoredProjectId, UUID ignoredOwnerId, String ignoredName) {
                throw new AssertionError("create must not be called");
            }

            @Override
            public Optional<ProjectDetails> findOwned(UUID suppliedOwnerId, UUID suppliedProjectId) {
                assertThat(transactionManager.active()).isTrue();
                assertThat(transactionManager.readOnly()).isTrue();
                assertThat(suppliedOwnerId).isEqualTo(ownerId);
                assertThat(suppliedProjectId).isEqualTo(projectId);
                return Optional.of(ownedProject);
            }

            @Override
            public Optional<ProjectDetails> rename(
                    UUID ignoredOwnerId,
                    UUID ignoredProjectId,
                    String ignoredName,
                    long ignoredVersion
            ) {
                throw new AssertionError("rename must not be called");
            }

            @Override
            public List<ProjectDetails> listOwned(UUID suppliedOwnerId, ProjectCursor cursor, int fetchLimit) {
                assertThat(transactionManager.active()).isTrue();
                assertThat(transactionManager.readOnly()).isTrue();
                assertThat(suppliedOwnerId).isEqualTo(ownerId);
                assertThat(cursor).isNull();
                assertThat(fetchLimit).isEqualTo(21);
                return List.of(ownedProject);
            }
        };
        ProjectCatalogService service = new ProjectCatalogService(projectStore, transactionManager);

        assertThat(service.findOwned(ownerId, projectId)).contains(ownedProject);
        assertThat(service.listOwned(ownerId, 20, null).items()).containsExactly(ownedProject);
        assertThat(transactionManager.transactionCount()).isEqualTo(2);
    }

    private static ProjectDetails projectDetails(UUID projectId, String name) {
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        return new ProjectDetails(projectId, name, 0, now, now);
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private boolean active;
        private boolean readOnly;
        private int transactionCount;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            active = true;
            readOnly = definition.isReadOnly();
            transactionCount++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            active = false;
        }

        @Override
        public void rollback(TransactionStatus status) {
            active = false;
        }

        boolean active() {
            return active;
        }

        boolean readOnly() {
            return readOnly;
        }

        int transactionCount() {
            return transactionCount;
        }
    }
}
