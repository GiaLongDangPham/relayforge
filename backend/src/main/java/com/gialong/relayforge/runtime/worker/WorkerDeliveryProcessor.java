package com.gialong.relayforge.runtime.worker;

import com.gialong.relayforge.delivery.api.DeliveryAttemptFinalizer;
import com.gialong.relayforge.delivery.api.DeliveryAttemptStarter;
import com.gialong.relayforge.delivery.api.DispatchInstruction;
import com.gialong.relayforge.delivery.api.DispatchObservation;
import com.gialong.relayforge.delivery.api.OutboundWebhookDispatcher;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles one bound claim. Its only retry loop is for database finalization after one completed dispatch.
 */
public final class WorkerDeliveryProcessor {

    private static final Duration[] FINALIZATION_RETRY_CAPS = {
            Duration.ofMillis(100),
            Duration.ofMillis(250),
            Duration.ofMillis(500),
            Duration.ofSeconds(1)
    };

    private final DeliveryAttemptStarter attemptStarter;
    private final OutboundWebhookDispatcher dispatcher;
    private final DeliveryAttemptFinalizer finalizer;
    private final Duration attemptExecutionLease;
    private final Duration finalizationMinimumRemaining;

    public WorkerDeliveryProcessor(
            DeliveryAttemptStarter attemptStarter,
            OutboundWebhookDispatcher dispatcher,
            DeliveryAttemptFinalizer finalizer,
            WorkerProperties properties
    ) {
        this.attemptStarter = Objects.requireNonNull(attemptStarter, "attemptStarter must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer must not be null");
        WorkerProperties requiredProperties = Objects.requireNonNull(properties, "properties must not be null");
        this.attemptExecutionLease = requiredProperties.attemptExecutionLease();
        this.finalizationMinimumRemaining = requiredProperties.finalizationMinimumRemaining();
    }

    public void process(WorkerClaimCoordinator.BoundClaim boundClaim) {
        try (boundClaim) {
            attemptStarter.start(boundClaim.claim(), attemptExecutionLease).ifPresent(this::dispatchAndFinalize);
        }
    }

    private void dispatchAndFinalize(DispatchInstruction instruction) {
        try (instruction; DispatchObservation observation = dispatcher.dispatch(instruction)) {
            finalizeWithoutResending(instruction, observation);
        }
    }

    private void finalizeWithoutResending(DispatchInstruction instruction, DispatchObservation observation) {
        int retryOrdinal = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                finalizer.finalizeAttempt(instruction, observation);
                return;
            } catch (RuntimeException ignored) {
                if (!finalizer.hasCurrentLease(instruction, finalizationMinimumRemaining)) {
                    return;
                }
                if (!sleep(equalJitter(FINALIZATION_RETRY_CAPS[Math.min(retryOrdinal, FINALIZATION_RETRY_CAPS.length - 1)]))) {
                    return;
                }
                retryOrdinal++;
            }
        }
    }

    private static Duration equalJitter(Duration cap) {
        long capMilliseconds = cap.toMillis();
        long halfMilliseconds = capMilliseconds / 2;
        return Duration.ofMillis(halfMilliseconds + ThreadLocalRandom.current().nextLong(halfMilliseconds + 1));
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
