package com.gialong.relayforge.delivery.application;
import com.gialong.relayforge.delivery.api.operations.RetentionCleanupResult;
import com.gialong.relayforge.delivery.api.operations.TerminalHistoryRetention;

import com.gialong.relayforge.delivery.api.operations.RetentionCleanupResult;
import com.gialong.relayforge.delivery.api.operations.TerminalHistoryRetention;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns one short PostgreSQL transaction per removable event graph. A graph is locked and rechecked
 * before deletion so a concurrent replay cannot leave retained history partially detached.
 */
@Service
final class TerminalHistoryRetentionService implements TerminalHistoryRetention {

    private final DeliveryStore deliveryStore;
    private final TransactionTemplate transaction;

    TerminalHistoryRetentionService(DeliveryStore deliveryStore, PlatformTransactionManager transactionManager) {
        this.deliveryStore = Objects.requireNonNull(deliveryStore, "deliveryStore must not be null");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public RetentionCleanupResult cleanExpiredTerminalHistory(int retentionDays, int maxGraphs) {
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("retentionDays must be positive");
        }
        if (maxGraphs <= 0) {
            throw new IllegalArgumentException("maxGraphs must be positive");
        }

        RetentionCleanupResult total = RetentionCleanupResult.empty();
        for (int graph = 0; graph < maxGraphs; graph++) {
            CleanupAttempt attempt = Objects.requireNonNull(
                    transaction.execute(status -> cleanOneGraph(retentionDays)),
                    "retention transaction returned no result"
            );
            total = total.plus(attempt.result());
            if (!attempt.candidateFound()) {
                break;
            }
        }
        return total;
    }

    private CleanupAttempt cleanOneGraph(int retentionDays) {
        Optional<UUID> candidate = deliveryStore.findNextExpiredTerminalEvent(retentionDays);
        if (candidate.isEmpty()) {
            return new CleanupAttempt(false, RetentionCleanupResult.empty());
        }

        UUID eventId = candidate.orElseThrow();
        if (!deliveryStore.tryLockRetentionGraph(eventId)) {
            return new CleanupAttempt(false, RetentionCleanupResult.empty());
        }
        if (!deliveryStore.lockRetentionEvent(eventId)) {
            return new CleanupAttempt(false, RetentionCleanupResult.empty());
        }
        if (!deliveryStore.isExpiredCompleteTerminalGraph(eventId, retentionDays)) {
            return new CleanupAttempt(true, RetentionCleanupResult.empty());
        }
        return new CleanupAttempt(true, deliveryStore.deleteRetentionGraph(eventId));
    }

    private record CleanupAttempt(boolean candidateFound, RetentionCleanupResult result) {
    }
}
