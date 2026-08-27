package com.gialong.relayforge.runtime.worker;

import com.gialong.relayforge.delivery.api.DeliveryAttemptFinalizer;
import com.gialong.relayforge.delivery.api.DeliveryAttemptStarter;
import com.gialong.relayforge.delivery.api.AttemptFinalizationResult;
import com.gialong.relayforge.delivery.api.DispatchInstruction;
import com.gialong.relayforge.delivery.api.DispatchObservation;
import com.gialong.relayforge.delivery.api.OutboundWebhookDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles one bound claim. Its only retry loop is for database finalization after one completed dispatch.
 */
public final class WorkerDeliveryProcessor {

    private static final Logger log = LoggerFactory.getLogger(WorkerDeliveryProcessor.class);
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
    private final WorkerOperationalMetrics metrics;

    public WorkerDeliveryProcessor(
            DeliveryAttemptStarter attemptStarter,
            OutboundWebhookDispatcher dispatcher,
            DeliveryAttemptFinalizer finalizer,
            WorkerProperties properties,
            WorkerOperationalMetrics metrics
    ) {
        this.attemptStarter = Objects.requireNonNull(attemptStarter, "attemptStarter must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.finalizer = Objects.requireNonNull(finalizer, "finalizer must not be null");
        WorkerProperties requiredProperties = Objects.requireNonNull(properties, "properties must not be null");
        this.attemptExecutionLease = requiredProperties.attemptExecutionLease();
        this.finalizationMinimumRemaining = requiredProperties.finalizationMinimumRemaining();
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    public void process(WorkerClaimCoordinator.BoundClaim boundClaim) {
        try (boundClaim) {
            attemptStarter.start(boundClaim.claim(), attemptExecutionLease).ifPresent(this::dispatchAndFinalize);
        }
    }

    private void dispatchAndFinalize(DispatchInstruction instruction) {
        try (instruction; DispatchObservation observation = dispatcher.dispatch(instruction)) {
            metrics.recordDispatch(observation);
            logDispatch(instruction, observation);
            finalizeWithoutResending(instruction, observation);
        }
    }

    private void finalizeWithoutResending(DispatchInstruction instruction, DispatchObservation observation) {
        int retryOrdinal = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                AttemptFinalizationResult result = finalizer.finalizeAttempt(instruction, observation);
                metrics.recordFinalization(result);
                logFinalization(instruction, result);
                return;
            } catch (RuntimeException ignored) {
                if (!finalizer.hasCurrentLease(instruction, finalizationMinimumRemaining)) {
                    metrics.recordFinalizationAbandoned();
                    log.atWarn()
                            .addKeyValue("event", "delivery_finalization_abandoned")
                            .addKeyValue("runtimeMode", "worker")
                            .addKeyValue("projectId", instruction.projectId())
                            .addKeyValue("eventId", instruction.eventId())
                            .addKeyValue("deliveryId", instruction.deliveryId())
                            .addKeyValue("attemptId", instruction.attemptId())
                            .addKeyValue("attemptNumber", instruction.attemptNumber())
                            .log("Delivery finalization left for lease recovery");
                    return;
                }
                if (!sleep(equalJitter(FINALIZATION_RETRY_CAPS[Math.min(retryOrdinal, FINALIZATION_RETRY_CAPS.length - 1)]))) {
                    return;
                }
                retryOrdinal++;
            }
        }
    }

    private static void logDispatch(DispatchInstruction instruction, DispatchObservation observation) {
        log.atInfo()
                .addKeyValue("event", "delivery_dispatch_completed")
                .addKeyValue("runtimeMode", "worker")
                .addKeyValue("projectId", instruction.projectId())
                .addKeyValue("eventId", instruction.eventId())
                .addKeyValue("deliveryId", instruction.deliveryId())
                .addKeyValue("attemptId", instruction.attemptId())
                .addKeyValue("attemptNumber", instruction.attemptNumber())
                .addKeyValue("outcome", observation.outcome())
                .addKeyValue("failureCode", observation.failureCode().map(Enum::name).orElse("none"))
                .addKeyValue("httpStatus", observation.httpStatus().isPresent() ? observation.httpStatus().getAsInt() : "none")
                .addKeyValue("durationMs", observation.duration().toMillis())
                .log("Outbound delivery dispatch completed");
    }

    private static void logFinalization(DispatchInstruction instruction, AttemptFinalizationResult result) {
        log.atInfo()
                .addKeyValue("event", "delivery_finalization_completed")
                .addKeyValue("runtimeMode", "worker")
                .addKeyValue("projectId", instruction.projectId())
                .addKeyValue("eventId", instruction.eventId())
                .addKeyValue("deliveryId", instruction.deliveryId())
                .addKeyValue("attemptId", instruction.attemptId())
                .addKeyValue("attemptNumber", instruction.attemptNumber())
                .addKeyValue("result", result)
                .log("Delivery finalization completed");
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
