package com.gialong.relayforge.delivery.application;

import com.gialong.relayforge.delivery.api.DeliveryAttemptRecovery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/**
 * Converts expired started attempts to immutable UNKNOWN observations without assuming receiver-side failure.
 */
@Service
final class DeliveryAttemptRecoveryService implements DeliveryAttemptRecovery {

    private final DeliveryStore deliveryStore;
    private final RetryDelayPolicy retryDelayPolicy;
    private final TransactionTemplate transaction;

    DeliveryAttemptRecoveryService(
            DeliveryStore deliveryStore,
            RetryDelayPolicy retryDelayPolicy,
            PlatformTransactionManager transactionManager
    ) {
        this.deliveryStore = Objects.requireNonNull(deliveryStore, "deliveryStore must not be null");
        this.retryDelayPolicy = Objects.requireNonNull(retryDelayPolicy, "retryDelayPolicy must not be null");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public int recoverExpiredStartedAttempts(int recoveryCapacity) {
        if (recoveryCapacity <= 0) {
            throw new IllegalArgumentException("recoveryCapacity must be positive");
        }
        return Objects.requireNonNull(
                transaction.execute(status -> recoverInTransaction(recoveryCapacity)),
                "post-attempt recovery transaction returned no result"
        );
    }

    private int recoverInTransaction(int recoveryCapacity) {
        List<ExpiredStartedAttempt> expiredAttempts = deliveryStore.lockExpiredStartedAttempts(recoveryCapacity);
        int recovered = 0;
        for (ExpiredStartedAttempt expiredAttempt : expiredAttempts) {
            CompletionDecision decision = retryDelayPolicy.forUnknownRecovery(expiredAttempt.attemptNumber());
            if (deliveryStore.recoverExpiredStartedAttempt(expiredAttempt, decision)) {
                recovered++;
            }
        }
        return recovered;
    }
}
