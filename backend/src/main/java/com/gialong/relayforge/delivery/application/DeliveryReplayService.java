package com.gialong.relayforge.delivery.application;

import com.gialong.relayforge.delivery.api.DeliveryReplayer;
import com.gialong.relayforge.delivery.api.ReplayDeliveryResult;
import com.gialong.relayforge.delivery.api.ReplayIdempotencyConflictException;
import com.gialong.relayforge.delivery.api.ReplayInvalidStateException;
import com.gialong.relayforge.project.api.ProjectCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owns the short project-authorized replay transaction; it enqueues durable work but never invokes HTTP. */
@Service
final class DeliveryReplayService implements DeliveryReplayer {

    private final ProjectCatalog projectCatalog;
    private final DeliveryStore deliveryStore;
    private final TransactionTemplate transaction;

    DeliveryReplayService(
            ProjectCatalog projectCatalog,
            DeliveryStore deliveryStore,
            PlatformTransactionManager transactionManager
    ) {
        this.projectCatalog = Objects.requireNonNull(projectCatalog, "projectCatalog must not be null");
        this.deliveryStore = Objects.requireNonNull(deliveryStore, "deliveryStore must not be null");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Optional<ReplayDeliveryResult> replay(UUID ownerId, UUID projectId, UUID sourceDeliveryId, String idempotencyKey) {
        UUID requiredOwnerId = Objects.requireNonNull(ownerId, "ownerId must not be null");
        UUID requiredProjectId = Objects.requireNonNull(projectId, "projectId must not be null");
        UUID requiredSourceDeliveryId = Objects.requireNonNull(sourceDeliveryId, "sourceDeliveryId must not be null");
        String requiredKey = PublishIdempotencyKey.requireValid(idempotencyKey);
        return Objects.requireNonNull(
                transaction.execute(status -> replayInTransaction(
                        requiredOwnerId,
                        requiredProjectId,
                        requiredSourceDeliveryId,
                        requiredKey
                )),
                "replay transaction returned no result"
        );
    }

    private Optional<ReplayDeliveryResult> replayInTransaction(
            UUID ownerId,
            UUID projectId,
            UUID sourceDeliveryId,
            String idempotencyKey
    ) {
        if (projectCatalog.findOwned(ownerId, projectId).isEmpty()) {
            return Optional.empty();
        }
        HistoryRecords.ReplayResult outcome = deliveryStore.replay(
                projectId,
                sourceDeliveryId,
                idempotencyKey,
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        return switch (outcome.outcome()) {
            case CREATED, EXISTING -> Optional.of(outcome.replay());
            case CONFLICT -> throw new ReplayIdempotencyConflictException();
            case SOURCE_NOT_FOUND -> Optional.empty();
            case SOURCE_NOT_EXHAUSTED -> throw new ReplayInvalidStateException();
        };
    }
}
