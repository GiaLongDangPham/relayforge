package com.gialong.relayforge.delivery.application;
import com.gialong.relayforge.delivery.api.processing.AttemptFinalizationResult;
import com.gialong.relayforge.delivery.api.processing.CircuitBreakerSettings;
import com.gialong.relayforge.delivery.api.processing.DeliveryAttemptFinalizer;
import com.gialong.relayforge.delivery.api.processing.DispatchInstruction;
import com.gialong.relayforge.delivery.api.processing.DispatchObservation;

import com.gialong.relayforge.delivery.api.processing.CircuitBreakerSettings;
import com.gialong.relayforge.delivery.api.processing.AttemptFinalizationResult;
import com.gialong.relayforge.delivery.api.processing.DeliveryAttemptFinalizer;
import com.gialong.relayforge.delivery.api.processing.DispatchInstruction;
import com.gialong.relayforge.delivery.api.processing.DispatchObservation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Objects;

/**
 * Owns short conditional result-finalization transactions. It never sends HTTP or extends leases.
 */
@Service
final class DeliveryAttemptFinalizationService implements DeliveryAttemptFinalizer {

    private final DeliveryStore deliveryStore;
    private final RetryDelayPolicy retryDelayPolicy;
    private final CircuitBreakerSettings circuitBreakerSettings;
    private final CircuitBreakerPolicy circuitBreakerPolicy;
    private final TransactionTemplate transaction;

    DeliveryAttemptFinalizationService(
            DeliveryStore deliveryStore,
            RetryDelayPolicy retryDelayPolicy,
            CircuitBreakerSettings circuitBreakerSettings,
            PlatformTransactionManager transactionManager
    ) {
        this.deliveryStore = Objects.requireNonNull(deliveryStore, "deliveryStore must not be null");
        this.retryDelayPolicy = Objects.requireNonNull(retryDelayPolicy, "retryDelayPolicy must not be null");
        this.circuitBreakerSettings = Objects.requireNonNull(
                circuitBreakerSettings,
                "circuitBreakerSettings must not be null"
        );
        this.circuitBreakerPolicy = new CircuitBreakerPolicy();
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public AttemptFinalizationResult finalizeAttempt(DispatchInstruction instruction, DispatchObservation observation) {
        DispatchInstruction requiredInstruction = Objects.requireNonNull(instruction, "instruction must not be null");
        try (AttemptCompletion completion = AttemptCompletion.observed(observation)) {
            CompletionDecision decision = retryDelayPolicy.forObserved(completion, requiredInstruction.attemptNumber());
            boolean qualifyingFailure = circuitBreakerPolicy.isQualifying(observation);
            return Objects.requireNonNull(
                    transaction.execute(status -> finalizeInTransaction(
                            requiredInstruction,
                            completion,
                            decision,
                            qualifyingFailure
                    )),
                    "finalization transaction returned no result"
            );
        }
    }

    @Override
    public boolean hasCurrentLease(DispatchInstruction instruction, Duration minimumRemaining) {
        DispatchInstruction requiredInstruction = Objects.requireNonNull(instruction, "instruction must not be null");
        Duration requiredMinimumRemaining = positiveDuration(minimumRemaining, "minimumRemaining");
        TransactionTemplate readOnlyTransaction = new TransactionTemplate(transaction.getTransactionManager());
        readOnlyTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        readOnlyTransaction.setReadOnly(true);
        return Boolean.TRUE.equals(readOnlyTransaction.execute(
                status -> deliveryStore.hasCurrentLease(requiredInstruction, requiredMinimumRemaining)
        ));
    }

    private AttemptFinalizationResult finalizeInTransaction(
            DispatchInstruction instruction,
            AttemptCompletion completion,
            CompletionDecision decision,
            boolean qualifyingFailure
    ) {
        if (deliveryStore.finalizeCurrentAttempt(instruction, completion, decision)) {
            deliveryStore.applyCircuitAfterObservedFinalization(
                    instruction,
                    qualifyingFailure,
                    circuitBreakerSettings
            );
            return AttemptFinalizationResult.FINALIZED;
        }
        return deliveryStore.recordLateDiagnostic(instruction, completion, java.util.UUID.randomUUID())
                ? AttemptFinalizationResult.LATE_DIAGNOSTIC_RECORDED
                : AttemptFinalizationResult.STALE;
    }

    private static Duration positiveDuration(Duration value, String name) {
        Duration required = Objects.requireNonNull(value, name + " must not be null");
        if (required.isNegative() || required.isZero() || required.toMillis() == 0) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
        return required;
    }
}
